package br.unb.baja.telemetry.support

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checkpoint 2.1 -- o schema nasce do Flyway, e as garantias que ele promete
 * sao do BANCO, nao de checagem no codigo.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
class MigrationTest(
    @Autowired private val jdbc: JdbcTemplate,
) {

    @Test
    fun `o Flyway aplicou as migrations e registrou no historico`() {
        val aplicadas = jdbc.queryForList(
            "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank",
        )
        assertTrue(aplicadas.isNotEmpty(), "flyway_schema_history vazio: as migrations nao rodaram")
        assertTrue(aplicadas.all { it["success"] == true }, "alguma migration falhou: $aplicadas")

        val versoes = aplicadas.map { it["version"] }
        assertTrue("1" in versoes, "V1 nao aplicada. historico: $aplicadas")
        assertTrue("2" in versoes, "V2 nao aplicada. historico: $aplicadas")
    }

    @Test
    fun `as tabelas base existem com as colunas do docs-06`() {
        val esperado = mapOf(
            "session" to listOf("id", "description", "started_at", "ended_at", "created_at"),
            "ingest_batch" to listOf(
                "id", "session_id", "device_id", "frame_count",
                "rejected_count", "received_at", "decoded_at",
            ),
            "signal_definition" to listOf(
                "dbc_version", "can_id", "signal_name", "start_bit", "bit_length",
                "byte_order", "is_signed", "scale", "offset_value",
                "min_value", "max_value", "unit",
            ),
        )
        for ((tabela, colunas) in esperado) {
            val achadas: List<String?> = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = ?",
                String::class.java, tabela,
            )
            assertEquals(colunas.sorted(), achadas.filterNotNull().sorted(), "colunas de $tabela")
        }
    }

    @Test
    fun `a chave primaria recusa o mesmo batchId duas vezes -- e a idempotencia do ADR-008`() {
        val sessao = "2026-08-24-teste-pk"
        val lote = UUID.randomUUID()
        jdbc.update("INSERT INTO session (id) VALUES (?) ON CONFLICT DO NOTHING", sessao)
        jdbc.update(
            "INSERT INTO ingest_batch (id, session_id, device_id, frame_count) VALUES (?,?,?,?)",
            lote, sessao, "esp32-node-1", 1000,
        )

        // O firmware retentou porque nao recebeu a resposta. O banco recusa --
        // e recusa mesmo que duas requisicoes cheguem ao mesmo tempo, o que um
        // `if (jaExiste)` no codigo nao garantiria.
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO ingest_batch (id, session_id, device_id, frame_count) VALUES (?,?,?,?)",
                lote, sessao, "esp32-node-1", 1000,
            )
        }

        jdbc.update("DELETE FROM ingest_batch WHERE id = ?", lote)
        jdbc.update("DELETE FROM session WHERE id = ?", sessao)
    }

    @Test
    fun `lote sem sessao correspondente e recusado`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO ingest_batch (id, session_id, device_id, frame_count) VALUES (?,?,?,?)",
                UUID.randomUUID(), "sessao-que-nao-existe", "esp32-node-1", 1,
            )
        }
    }

    @Test
    fun `byte_order so aceita BIG ou LITTLE`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                """
                INSERT INTO signal_definition
                  (dbc_version, can_id, signal_name, start_bit, bit_length,
                   byte_order, is_signed, scale, offset_value)
                VALUES ('x', 256, 'rpm', 7, 16, 'MEIO-TERMO', false, 0.25, 0)
                """,
            )
        }
    }
}
