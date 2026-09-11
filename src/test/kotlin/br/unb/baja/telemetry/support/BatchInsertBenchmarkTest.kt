package br.unb.baja.telemetry.support

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * O aceite do checkpoint 2.5: medir insercao linha a linha contra em lote, e
 * confirmar que a flag do driver esta fazendo alguma coisa (ADR-004).
 *
 * Nao e microbenchmark de precisao -- e uma comparacao de ORDEM DE GRANDEZA,
 * que e o que a decisao exige. Roda contra o mesmo container das outras suites.
 */
// Sobe o contexto so para o Flyway criar o schema no container; as medicoes
// usam conexoes JDBC diretas, para controlar os parametros da URL.
@SpringBootTest
@Import(TestcontainersConfig::class)
class BatchInsertBenchmarkTest {

    private val LINHAS = 5_000 // um lote cheio, pelo limite do docs/03

    /**
     * Monta a URL do zero em vez de acrescentar a do Testcontainers -- que ja
     * traz `reWriteBatchedInserts=true`. Anexar produziria o mesmo parametro
     * duas vezes com valores diferentes, e o resultado dependeria de qual o
     * driver escolhe.
     */
    private fun conectar(params: String): Connection {
        val c = PostgresDeTeste.instancia
        val base = "jdbc:postgresql://${c.host}:${c.getMappedPort(5432)}/${c.databaseName}"
        val url = if (params.isBlank()) base else "$base?$params"
        return DriverManager.getConnection(url, c.username, c.password)
    }

    private fun preparar(conn: Connection, sessao: String, lote: UUID) {
        conn.createStatement().use { st ->
            st.execute("INSERT INTO session (id) VALUES ('$sessao') ON CONFLICT DO NOTHING")
            st.execute(
                "INSERT INTO ingest_batch (id, session_id, device_id, frame_count) " +
                    "VALUES ('$lote', '$sessao', 'bench', $LINHAS) ON CONFLICT DO NOTHING",
            )
        }
    }

    private val SQL = """
        INSERT INTO raw_frame
          (frame_time, session_id, device_id, can_id, payload, batch_id, received_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()

    private fun inserir(conn: Connection, sessao: String, lote: UUID, emLote: Boolean) {
        val t0 = Instant.parse("2026-08-22T09:00:00Z")
        val payload = ByteArray(8) { 0x5B }
        conn.autoCommit = false
        conn.prepareStatement(SQL).use { ps ->
            repeat(LINHAS) { i ->
                ps.setTimestamp(1, Timestamp.from(t0.plusMillis(i * 2L)))
                ps.setString(2, sessao)
                ps.setString(3, "bench")
                ps.setInt(4, 256)
                ps.setBytes(5, payload)
                ps.setObject(6, lote)
                ps.setTimestamp(7, Timestamp.from(t0))
                if (emLote) ps.addBatch() else ps.executeUpdate()
            }
            if (emLote) ps.executeBatch()
        }
        conn.commit()
    }

    private fun limpar(conn: Connection, sessao: String) {
        conn.autoCommit = true
        conn.createStatement().use {
            it.execute("DELETE FROM raw_frame WHERE session_id = '$sessao'")
            it.execute("DELETE FROM ingest_batch WHERE session_id = '$sessao'")
            it.execute("DELETE FROM session WHERE id = '$sessao'")
        }
    }

    private fun medir(rotulo: String, params: String, emLote: Boolean): Long {
        val sessao = "2026-08-22-bench-${UUID.randomUUID().toString().take(8)}"
        val lote = UUID.randomUUID()
        return conectar(params).use { conn ->
            preparar(conn, sessao, lote)
            // uma passada de aquecimento, para nao medir JIT nem cache frio
            inserir(conn, sessao, lote, emLote); limpar(conn, sessao)
            preparar(conn, sessao, lote)

            val t = measureTime { inserir(conn, sessao, lote, emLote) }
            limpar(conn, sessao)
            val ms = t.inWholeMilliseconds.coerceAtLeast(1)
            println("  %-46s %6d ms   %,9d linhas/s".format(rotulo, ms, LINHAS * 1000L / ms))
            ms
        }
    }

    @Test
    fun `insercao em lote e ordens de grandeza mais rapida que linha a linha`() {
        println("\n  === $LINHAS linhas em raw_frame ===")
        val linhaALinha = medir("1. linha a linha (executeUpdate por linha)", "", false)
        val loteSemFlag = medir("2. em lote, SEM reWriteBatchedInserts", "reWriteBatchedInserts=false", true)
        val loteComFlag = medir("3. em lote, COM reWriteBatchedInserts", "reWriteBatchedInserts=true", true)
        val nomeErrado = medir("4. em lote, com o nome do MySQL (errado)", "rewriteBatchedStatements=true", true)

        println()
        println("  em lote vs linha a linha : %.1fx".format(linhaALinha.toDouble() / loteComFlag))
        println("  a flag sozinha           : %.1fx".format(loteSemFlag.toDouble() / loteComFlag))
        println("  nome do MySQL vs nome certo: %.1fx MAIS LENTO -- o driver do Postgres ignora"
            .format(nomeErrado.toDouble() / loteComFlag))
        println()

        assertTrue(
            loteComFlag < linhaALinha,
            "em lote ($loteComFlag ms) deveria ser mais rapido que linha a linha ($linhaALinha ms)",
        )
        // O nome do MySQL nao pode se comportar como a flag ligada. Se um dia
        // este assert falhar, o driver passou a aceitar o nome -- e o comentario
        // no application.properties precisa ser revisto.
        assertTrue(
            nomeErrado > loteComFlag * 2,
            "com o nome do MySQL ($nomeErrado ms) o desempenho deveria ficar longe do " +
                "nome correto ($loteComFlag ms) -- o driver do Postgres deveria ignorar",
        )
    }
}
