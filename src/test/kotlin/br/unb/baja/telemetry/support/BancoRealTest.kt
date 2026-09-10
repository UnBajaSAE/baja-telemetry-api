package br.unb.baja.telemetry.support

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Prova que a suite roda contra um Postgres DE VERDADE, e nao contra um banco
 * em memoria (ADR-003).
 *
 * Nao e teste cerimonial: se um dia alguem trocar por H2 para "acelerar a
 * suite", estes testes quebram e explicam o porque. O H2 nao tem a extensao,
 * nao tem `create_hypertable`, e nao se comporta como o banco de producao --
 * a suite passaria a testar exatamente o que o projeto nao usa.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
class BancoRealTest(
    @Autowired private val jdbc: JdbcTemplate,
) {

    @Test
    fun `o banco do teste e um PostgreSQL 17, nao um banco em memoria`() {
        val versao = assertNotNull(jdbc.queryForObject("SELECT version()", String::class.java))
        assertTrue(versao.startsWith("PostgreSQL 17"), "esperava PostgreSQL 17, veio: $versao")

        val produto = assertNotNull(
            jdbc.dataSource?.connection?.use { it.metaData.databaseProductName },
        )
        assertEquals("PostgreSQL", produto, "a suite precisa rodar contra Postgres real")
    }

    @Test
    fun `a extensao TimescaleDB esta habilitada`() {
        val versao = jdbc.queryForObject(
            "SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'",
            String::class.java,
        )
        assertNotNull(versao, "sem a extensao, metade do projeto nao existe")
    }

    @Test
    fun `create_hypertable funciona -- e o que nenhum banco em memoria faz`() {
        jdbc.execute("DROP TABLE IF EXISTS prova_hypertable CASCADE")
        jdbc.execute(
            """
            CREATE TABLE prova_hypertable (
                ts    TIMESTAMPTZ NOT NULL,
                valor DOUBLE PRECISION NOT NULL
            )
            """,
        )
        jdbc.execute(
            "SELECT create_hypertable('prova_hypertable', 'ts', " +
                "chunk_time_interval => INTERVAL '1 day')",
        )

        // Dois dias de dado devem gerar dois chunks, criados pelo banco sozinho.
        jdbc.execute(
            """
            INSERT INTO prova_hypertable
            SELECT ts, 1.0 FROM generate_series(
                '2026-08-22'::timestamptz, '2026-08-23'::timestamptz, '6 hours') AS ts
            """,
        )

        val chunks = jdbc.queryForObject(
            "SELECT count(*) FROM timescaledb_information.chunks " +
                "WHERE hypertable_name = 'prova_hypertable'",
            Int::class.java,
        )
        assertEquals(2, chunks, "a janela de 1 dia deveria ter criado 2 chunks")

        jdbc.execute("DROP TABLE prova_hypertable CASCADE")
    }
}
