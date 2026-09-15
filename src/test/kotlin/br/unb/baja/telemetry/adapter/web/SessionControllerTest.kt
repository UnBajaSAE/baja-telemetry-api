package br.unb.baja.telemetry.adapter.web

import br.unb.baja.telemetry.application.IncomingFrame
import br.unb.baja.telemetry.application.IngestService
import br.unb.baja.telemetry.domain.session.SessionId
import br.unb.baja.telemetry.support.TestcontainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checkpoint 3.1 -- o primeiro endpoint de leitura.
 *
 * Os testes filtram as PROPRIAS sessoes: o endpoint devolve todas, e a suite
 * compartilha o banco (licao do checkpoint 2.5). Contar o total tornaria estes
 * testes dependentes da ordem de execucao.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class SessionControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val ingest: IngestService,
    @Autowired private val jdbc: JdbcTemplate,
    // O mapper do proprio Spring: usar o mesmo que serializa a resposta evita
    // que o teste passe por acidente com uma configuracao diferente.
    @Autowired private val json: ObjectMapper,
) {
    private val marca = UUID.randomUUID().toString().take(8)

    private fun sessao(dia: String, sufixo: String) =
        SessionId.parseOrNull("$dia-lista-$marca-$sufixo")!!

    /** Lista so as sessoes deste teste, ja desserializadas. */
    private fun minhasSessoes(): List<Map<String, Any?>> {
        val corpo = mockMvc.get("/api/v1/sessions")
            .andReturn().response.contentAsString
        @Suppress("UNCHECKED_CAST")
        val todas = json.readValue(corpo, List::class.java) as List<Map<String, Any?>>
        return todas.filter { (it["id"] as String).contains(marca) }
    }

    private fun enviar(s: SessionId, frames: List<IncomingFrame>) =
        ingest.ingest(UUID.randomUUID(), s, "esp32-node-1", frames)

    @Test
    fun `a sessao aparece com duracao e contagem vindas dos frames`() {
        val s = sessao("2026-08-24", "a")
        // 09:00:00 ate 09:00:02 -- dois segundos de duracao
        val inicio = Instant.parse("2026-08-24T09:00:00Z").toEpochMilli()
        enviar(s, (0..200).map { IncomingFrame(inicio + it * 10, 0x100, "3E805B0000000000") })

        val vista = assertNotNull(minhasSessoes().firstOrNull { it["id"] == s.value })
        assertEquals(201, (vista["frameCount"] as Number).toInt())
        assertEquals(0, (vista["rejectedCount"] as Number).toInt())
        assertEquals(1, (vista["batchCount"] as Number).toInt())
        assertEquals(2L, (vista["durationSeconds"] as Number).toLong())
        assertTrue((vista["startedAt"] as String).startsWith("2026-08-24T09:00:00"))
    }

    @Test
    fun `a duracao vem do relogio do DISPOSITIVO, nao do horario de chegada`() {
        val s = sessao("2026-08-24", "b")
        // Frames lidos em 2026-08-24, mas recebidos agora -- o buffer ficou
        // parado no cartao SD. A sessao pertence ao momento da PISTA (ADR-004).
        val leitura = Instant.parse("2026-08-24T14:30:00Z").toEpochMilli()
        enviar(s, (0..99).map { IncomingFrame(leitura + it * 100, 0x100, "3E805B0000000000") })

        val vista = assertNotNull(minhasSessoes().firstOrNull { it["id"] == s.value })
        assertTrue(
            (vista["startedAt"] as String).startsWith("2026-08-24T14:30"),
            "startedAt deveria ser o horario da leitura, veio ${vista["startedAt"]}",
        )
        assertEquals(9L, (vista["durationSeconds"] as Number).toLong())
    }

    @Test
    fun `varios lotes na mesma sessao somam, e a duracao cobre todos`() {
        val s = sessao("2026-08-24", "c")
        val t0 = Instant.parse("2026-08-24T10:00:00Z").toEpochMilli()
        enviar(s, (0..49).map { IncomingFrame(t0 + it * 10, 0x100, "3E805B0000000000") })
        enviar(s, (0..49).map { IncomingFrame(t0 + 60_000 + it * 10, 0x100, "3E805B0000000000") })

        val vista = assertNotNull(minhasSessoes().firstOrNull { it["id"] == s.value })
        assertEquals(100, (vista["frameCount"] as Number).toInt())
        assertEquals(2, (vista["batchCount"] as Number).toInt())
        assertEquals(60L, (vista["durationSeconds"] as Number).toLong(), "do 1o frame ao ultimo")
    }

    @Test
    fun `sessao sem nenhum lote aparece na lista, com contagem zero`() {
        val s = sessao("2026-08-24", "d")
        jdbc.update("INSERT INTO session (id) VALUES (?) ON CONFLICT DO NOTHING", s.value)

        val vista = assertNotNull(
            minhasSessoes().firstOrNull { it["id"] == s.value },
            "uma sessao recem-criada nao pode sumir da lista",
        )
        assertEquals(0, (vista["frameCount"] as Number).toInt())
        assertEquals(0, (vista["batchCount"] as Number).toInt())
        assertNull(vista["startedAt"])
        assertNull(vista["durationSeconds"])
    }

    @Test
    fun `frames rejeitados entram na contagem de rejeitados, nao na de frames guardados`() {
        val s = sessao("2026-08-24", "e")
        val t0 = Instant.parse("2026-08-24T11:00:00Z").toEpochMilli()
        enviar(
            s,
            listOf(
                IncomingFrame(t0, 0x100, "3E805B0000000000"),
                IncomingFrame(t0 + 10, 0x100, "3E805B000000000"), // hex impar
            ),
        )

        val vista = assertNotNull(minhasSessoes().firstOrNull { it["id"] == s.value })
        assertEquals(2, (vista["frameCount"] as Number).toInt(), "frameCount conta o RECEBIDO")
        assertEquals(1, (vista["rejectedCount"] as Number).toInt())
    }

    @Test
    fun `a lista vem da sessao mais recente para a mais antiga`() {
        val antiga = sessao("2026-07-10", "f")
        val nova = sessao("2026-08-24", "g")
        enviar(antiga, listOf(IncomingFrame(Instant.parse("2026-07-10T08:00:00Z").toEpochMilli(), 0x100, "3E805B0000000000")))
        enviar(nova, listOf(IncomingFrame(Instant.parse("2026-08-24T08:00:00Z").toEpochMilli(), 0x100, "3E805B0000000000")))

        val ids = minhasSessoes().map { it["id"] as String }
        assertTrue(
            ids.indexOf(nova.value) < ids.indexOf(antiga.value),
            "a mais recente tem que vir antes. veio: $ids",
        )
    }
}
