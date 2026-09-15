package br.unb.baja.telemetry.application

import br.unb.baja.telemetry.domain.session.SessionId
import br.unb.baja.telemetry.support.TestcontainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checkpoint 2.6 -- a idempotencia do ADR-008, ponta a ponta.
 *
 * O cenario que isto protege nao e o envio falhar. E o envio DAR CERTO e a
 * resposta se perder: o ESP32 nao sabe se chegou e manda de novo. Sem protecao,
 * meia sessao duplica -- e dado duplicado nao gera erro, gera media errada num
 * grafico que parece normal.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
class IdempotenciaTest(
    @Autowired private val ingest: IngestService,
    @Autowired private val jdbc: JdbcTemplate,
) {

    private fun sessaoNova() = SessionId.parseOrNull(
        "2026-08-24-idem-${UUID.randomUUID().toString().take(8)}",
    )!!

    private fun frames(n: Int) = (0 until n).map {
        IncomingFrame(1787572800000L + it * 10, 0x100, "3E805B0000000000")
    }

    private fun contarCru(s: SessionId) = jdbc.queryForObject(
        "SELECT count(*) FROM raw_frame WHERE session_id = ?", Int::class.java, s.value,
    )!!

    private fun contarSinais(s: SessionId) = jdbc.queryForObject(
        "SELECT count(*) FROM signal_point WHERE session_id = ?", Int::class.java, s.value,
    )!!

    // ------------------------------------------------------------- o aceite

    @Test
    fun `o mesmo lote enviado duas vezes grava uma vez so`() {
        val sessao = sessaoNova()
        val lote = UUID.randomUUID()
        val fs = frames(50)

        val primeira = ingest.ingest(lote, sessao, "esp32-node-1", fs)
        assertFalse(primeira.duplicate, "a primeira nao pode ser duplicata")
        assertEquals(50, primeira.stored)
        assertEquals(100, primeira.signalsStored, "50 frames x 2 sinais (rpm, temp)")

        val crusDepoisDaPrimeira = contarCru(sessao)
        val sinaisDepoisDaPrimeira = contarSinais(sessao)

        // O WiFi caiu na hora da resposta: o firmware retenta com o MESMO batchId
        val segunda = ingest.ingest(lote, sessao, "esp32-node-1", fs)
        assertTrue(segunda.duplicate, "a segunda tinha que ser reconhecida como duplicata")
        assertEquals(0, segunda.stored, "nada pode ser gravado de novo")

        assertEquals(crusDepoisDaPrimeira, contarCru(sessao), "raw_frame duplicou")
        assertEquals(sinaisDepoisDaPrimeira, contarSinais(sessao), "signal_point duplicou")
        assertEquals(50, contarCru(sessao))
        assertEquals(100, contarSinais(sessao))
    }

    @Test
    fun `dez reenvios seguidos continuam gravando uma vez so`() {
        val sessao = sessaoNova()
        val lote = UUID.randomUUID()
        val fs = frames(20)

        ingest.ingest(lote, sessao, "esp32-node-1", fs)
        repeat(9) { assertTrue(ingest.ingest(lote, sessao, "esp32-node-1", fs).duplicate) }

        assertEquals(20, contarCru(sessao), "dez envios deveriam ter gravado 20 frames, uma vez")
    }

    // ------------------------------------------- o caso que separa banco de `if`

    /**
     * Duas requisicoes com o mesmo batchId chegando AO MESMO TEMPO.
     *
     * E o cenario que um `if (jaExiste)` no codigo nao cobre: as duas consultam
     * "existe?", as duas ouvem "nao", as duas gravam. A chave primaria nao tem
     * essa fresta -- e e por isso que o ADR-008 poe a protecao no banco.
     */
    @Test
    fun `envios simultaneos com o mesmo batchId -- exatamente um grava`() {
        val sessao = sessaoNova()
        val lote = UUID.randomUUID()
        val fs = frames(30)

        // A sessao PRECISA existir antes da largada. Sem isto, as transacoes
        // serializam no upsert da sessao -- que roda antes da checagem do lote --
        // e a concorrencia que se quer testar nunca acontece. Descoberto
        // sabotando a protecao: a versao ingenua passava.
        jdbc.update("INSERT INTO session (id) VALUES (?) ON CONFLICT DO NOTHING", sessao.value)

        val paralelas = 8
        val largada = CyclicBarrier(paralelas)
        val pool = Executors.newFixedThreadPool(paralelas)

        val resultados = try {
            pool.invokeAll(
                (1..paralelas).map {
                    Callable {
                        largada.await(10, TimeUnit.SECONDS) // todas partem juntas
                        runCatching { ingest.ingest(lote, sessao, "esp32-node-1", fs) }
                    }
                },
            ).map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val okes = resultados.mapNotNull { it.getOrNull() }
        val falhas = resultados.mapNotNull { it.exceptionOrNull() }

        // Nenhuma pode explodir. Com a protecao num `if (jaExiste)` em vez da
        // chave primaria, varias threads passam pela verificacao e colidem no
        // INSERT -- viram DuplicateKeyException, ou seja, HTTP 500 para o
        // firmware. Verificado sabotando o JdbcIngestBatchStore: 6 de 8 falhavam.
        assertEquals(
            paralelas, okes.size,
            "${falhas.size} requisicao(oes) explodiram em vez de responder duplicate: " +
                falhas.take(2).joinToString { "${it::class.simpleName}: ${it.message?.take(90)}" },
        )

        val gravaram = okes.count { !it.duplicate }
        assertEquals(1, gravaram, "exatamente uma das $paralelas deveria ter gravado")
        assertEquals(paralelas - 1, okes.count { it.duplicate })

        assertEquals(30, contarCru(sessao), "$paralelas envios simultaneos duplicaram o cru")
        assertEquals(60, contarSinais(sessao))
    }

    // ----------------------------------------- o limite conhecido do ADR-008

    /**
     * A protecao e por LOTE, nao por conteudo. Os mesmos frames com batchId novo
     * entram de novo -- e isso e o limite que o ADR-008 registra como conhecido.
     *
     * A mitigacao e do firmware: gravar o batchId no cartao SD junto com o
     * buffer, para que um reinicio nao gere id novo para o mesmo recorte.
     */
    @Test
    fun `os mesmos frames com batchId diferente entram de novo -- limite conhecido`() {
        val sessao = sessaoNova()
        val fs = frames(10)

        ingest.ingest(UUID.randomUUID(), sessao, "esp32-node-1", fs)
        val outro = ingest.ingest(UUID.randomUUID(), sessao, "esp32-node-1", fs)

        assertFalse(outro.duplicate, "batchId novo nao e duplicata, por desenho")
        assertEquals(20, contarCru(sessao), "e por isso que o firmware PRECISA persistir o batchId")
    }

    @Test
    fun `lote duplicado nao remarca decoded_at nem altera o registro original`() {
        val sessao = sessaoNova()
        val lote = UUID.randomUUID()
        ingest.ingest(lote, sessao, "esp32-node-1", frames(5))

        val antes = assertNotNull(
            jdbc.queryForMap("SELECT frame_count, decoded_at FROM ingest_batch WHERE id = ?", lote),
        )
        // Reenvio com MENOS frames: nao pode sobrescrever o registro original
        ingest.ingest(lote, sessao, "esp32-node-1", frames(2))

        val depois = jdbc.queryForMap("SELECT frame_count, decoded_at FROM ingest_batch WHERE id = ?", lote)
        assertEquals(antes["frame_count"], depois["frame_count"])
        assertEquals(antes["decoded_at"], depois["decoded_at"])
    }
}
