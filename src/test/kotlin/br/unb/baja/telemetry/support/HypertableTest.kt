package br.unb.baja.telemetry.support

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checkpoint 2.2 -- `raw_frame` e hypertable de verdade, e as tres decisoes
 * documentadas no docs/06 §4 valem no banco.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
class HypertableTest(
    @Autowired private val jdbc: JdbcTemplate,
) {

    private val sessao = "2026-08-22-hypertable"

    // Os literais de tempo levam o fuso EXPLICITO. Sem o `+00`, o Postgres
    // interpreta na zona da sessao (Brasilia, -03) e desloca a janela em 3h --
    // o suficiente para o chunk das 00:00 UTC ficar de fora do filtro.
    private fun semear(de: String, ate: String, passo: String = "6 hours"): Int {
        jdbc.update("INSERT INTO session (id) VALUES (?) ON CONFLICT DO NOTHING", sessao)
        val lote = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ingest_batch (id, session_id, device_id, frame_count) VALUES (?,?,?,0)",
            lote, sessao, "esp32-node-1",
        )
        return jdbc.update(
            """
            INSERT INTO raw_frame (frame_time, session_id, device_id, can_id, payload, batch_id, received_at)
            SELECT ts, ?, 'esp32-node-1', 256, '\x3E805B0000000000'::bytea, ?, now()
            FROM generate_series(?::timestamptz, ?::timestamptz, ?::interval) AS ts
            """,
            sessao, lote, de, ate, passo,
        )
    }

    @Test
    fun `raw_frame e hypertable particionada por frame_time`() {
        val col = jdbc.queryForObject(
            "SELECT column_name FROM timescaledb_information.dimensions " +
                "WHERE hypertable_name = 'raw_frame'",
            String::class.java,
        )
        assertEquals("frame_time", col, "deve particionar pelo relogio do DISPOSITIVO, nao do servidor")
    }

    @Test
    fun `a janela do chunk e de 1 dia, como o docs-06 decidiu`() {
        val intervalo = jdbc.queryForObject(
            "SELECT time_interval::text FROM timescaledb_information.dimensions " +
                "WHERE hypertable_name = 'raw_frame'",
            String::class.java,
        )
        assertEquals("1 day", intervalo)
    }

    /**
     * As fronteiras de chunk sao alinhadas em UTC, nao no fuso local -- por isso
     * a comparacao converte explicitamente. Em Brasilia (-03) a meia-noite UTC
     * cai as 21h locais: uma sessao noturna que atravesse esse horario ocupa
     * DOIS chunks. Nao quebra nada, so rende menos (docs/06 §4.3).
     */
    @Test
    fun `dado cruzando dias cria um chunk por dia, sozinho`() {
        val linhas = semear("2026-08-22 00:00+00", "2026-08-24 00:00+00")
        assertTrue(linhas > 0, "nada foi inserido")

        // A janela e explicita porque outros testes tambem gravam em raw_frame
        // desde que o /ingest passou a persistir (checkpoint 2.5). Contar TODOS
        // os chunks tornaria este teste dependente da ordem de execucao.
        val chunks = jdbc.queryForList(
            """
            SELECT (range_start AT TIME ZONE 'UTC')::date::text AS dia
            FROM timescaledb_information.chunks
            WHERE hypertable_name = 'raw_frame'
              AND range_start >= '2026-08-22 00:00+00'::timestamptz
              AND range_end   <= '2026-08-25 00:00+00'::timestamptz
            ORDER BY range_start
            """,
        ).map { it["dia"] as String }

        assertEquals(listOf("2026-08-22", "2026-08-23", "2026-08-24"), chunks)
    }

    @Test
    fun `o planejador abre so o chunk do dia consultado`() {
        semear("2026-08-22 00:00+00", "2026-08-24 00:00+00")

        val plano = jdbc.queryForList(
            """
            EXPLAIN SELECT count(*) FROM raw_frame
            WHERE session_id = ?
              AND frame_time >= '2026-08-24 00:00+00' AND frame_time < '2026-08-25 00:00+00'
            """,
            sessao,
        ).joinToString("\n") { it.values.first().toString() }

        val abertos = Regex("_hyper_\\d+_(\\d+)_chunk").findAll(plano).map { it.value }.toSet()
        assertEquals(1, abertos.size, "esperava um chunk aberto, o plano abriu $abertos:\n$plano")
    }

    @Test
    fun `raw_frame NAO tem chave primaria -- e decisao, nao esquecimento`() {
        // Hypertable recusa indice unico que nao inclua a coluna de tempo
        // (docs/06 §4.1). A unicidade e garantida no nivel do lote, pela PK de
        // ingest_batch. PK aqui seria custo de indice sem funcao.
        val pks = jdbc.queryForList(
            "SELECT conname FROM pg_constraint WHERE conrelid = 'raw_frame'::regclass AND contype = 'p'",
        )
        assertTrue(pks.isEmpty(), "raw_frame nao deveria ter PK, mas tem: $pks")
    }

    @Test
    fun `raw_frame NAO tem chave estrangeira -- custaria uma verificacao por linha`() {
        val fks = jdbc.queryForList(
            "SELECT conname FROM pg_constraint WHERE conrelid = 'raw_frame'::regclass AND contype = 'f'",
        )
        assertTrue(fks.isEmpty(), "FK numa hypertable paga verificacao por linha inserida: $fks")
    }

    @Test
    fun `a politica de compressao esta registrada`() {
        val job = jdbc.queryForList(
            "SELECT schedule_interval::text FROM timescaledb_information.jobs " +
                "WHERE hypertable_name = 'raw_frame' AND proc_name LIKE '%compress%'",
        )
        assertTrue(job.isNotEmpty(), "sem politica de compressao registrada para raw_frame")
    }

    @Test
    fun `nao existe politica de retencao -- apagar o cru quebraria o ADR-001`() {
        val retencao = jdbc.queryForList(
            "SELECT proc_name FROM timescaledb_information.jobs " +
                "WHERE hypertable_name = 'raw_frame' AND proc_name LIKE '%retention%'",
        )
        assertTrue(
            retencao.isEmpty(),
            "raw_frame e a fonte da verdade: apagar elimina a capacidade de reprocessar",
        )
    }
}
