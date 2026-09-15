package br.unb.baja.telemetry.adapter.web

import br.unb.baja.telemetry.application.IncomingFrame
import br.unb.baja.telemetry.application.IngestService
import br.unb.baja.telemetry.domain.can.FrameEncoder
import br.unb.baja.telemetry.domain.can.dbc.DbcDatabase
import br.unb.baja.telemetry.domain.session.SessionId
import br.unb.baja.telemetry.support.TestcontainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checkpoint 3.2 -- o resumo por sinal, lido do agregado continuo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class SessionSummaryTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val ingest: IngestService,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val dbc: DbcDatabase,
) {
    private val marca = UUID.randomUUID().toString().take(8)
    private val motor = dbc.message(0x100)!!
    private val rpm = dbc.signal(0x100, "rpm")!!
    private val temp = dbc.signal(0x100, "temp")!!

    private fun sessao(sufixo: String) = SessionId.parseOrNull("2026-08-24-resumo-$marca-$sufixo")!!

    /** Monta um frame com valores fisicos conhecidos. */
    private fun frame(ms: Long, valorRpm: Double, valorTemp: Double) = IncomingFrame(
        ms, 0x100,
        FrameEncoder.encode(motor.dlc, mapOf(rpm to valorRpm, temp to valorTemp))
            .joinToString("") { "%02X".format(it) },
    )

    private fun resumo(id: String): List<Map<String, Any?>> {
        val corpo = mockMvc.get("/api/v1/sessions/$id/summary")
            .andReturn().response.contentAsString
        @Suppress("UNCHECKED_CAST")
        return json.readValue(corpo, List::class.java) as List<Map<String, Any?>>
    }

    private fun porSinal(id: String) = resumo(id).associateBy { it["signal"] as String }

    @Test
    fun `minimo, maximo e contagem batem com o que foi enviado`() {
        val s = sessao("a")
        val t0 = Instant.parse("2026-08-24T09:00:00Z").toEpochMilli()
        val rpms = listOf(1000.0, 2000.0, 3000.0, 4000.0, 2500.0)
        ingest.ingest(
            UUID.randomUUID(), s, "esp32-node-1",
            rpms.mapIndexed { i, v -> frame(t0 + i * 10L, v, 50.0) },
        )

        val r = assertNotNull(porSinal(s.value)["rpm"])
        assertEquals(1000.0, (r["min"] as Number).toDouble())
        assertEquals(4000.0, (r["max"] as Number).toDouble())
        assertEquals(5L, (r["count"] as Number).toLong())
        assertEquals(0L, (r["invalidCount"] as Number).toLong())
    }

    @Test
    fun `dado recem-gravado ja aparece, mesmo sem o agregado ter materializado`() {
        // A politica de atualizacao roda a cada minuto. O TimescaleDB une a parte
        // ja materializada com uma leitura ao vivo do que e mais recente -- sem
        // isso, uma sessao acabada de subir apareceria vazia.
        val s = sessao("b")
        val agora = Instant.now().toEpochMilli()
        ingest.ingest(
            UUID.randomUUID(), s, "esp32-node-1",
            (0..9).map { frame(agora + it * 10L, 3000.0, 60.0) },
        )

        val r = assertNotNull(
            porSinal(s.value)["rpm"],
            "dado de agora sumiu do resumo: a agregacao em tempo real esta desligada?",
        )
        assertEquals(10L, (r["count"] as Number).toLong())
    }

    @Test
    fun `a media e PONDERADA -- janelas desiguais nao podem pesar igual`() {
        val s = sessao("c")
        val t0 = Instant.parse("2026-08-24T10:00:00Z").toEpochMilli()

        // Segundo 1: duas leituras a 800 rpm (carro parado no box)
        // Segundo 2: cem leituras a 4000 rpm (acelerando)
        val frames = buildList {
            repeat(2) { add(frame(t0 + it * 400L, 800.0, 50.0)) }
            repeat(100) { add(frame(t0 + 1000L + it * 5L, 4000.0, 50.0)) }
        }
        ingest.ingest(UUID.randomUUID(), s, "esp32-node-1", frames)

        val r = assertNotNull(porSinal(s.value)["rpm"])
        val esperada = (2 * 800.0 + 100 * 4000.0) / 102 // ~3937,25
        val mediaDasMedias = (800.0 + 4000.0) / 2 // 2400 -- o jeito errado

        val veio = (r["avg"] as Number).toDouble()
        assertTrue(
            abs(veio - esperada) < 0.5,
            "media ponderada deveria ser ~$esperada, veio $veio " +
                "(se veio ~$mediaDasMedias, o agregado esta guardando avg em vez de sum+count)",
        )
    }

    @Test
    fun `ponto fora de faixa e contado, mas NAO contamina a estatistica`() {
        val s = sessao("d")
        val t0 = Instant.parse("2026-08-24T11:00:00Z").toEpochMilli()
        // 0xFFFF em rpm = 16.383,75, acima do maximo de 8.000 do DBC
        val foraDeFaixa = IncomingFrame(t0, 0x100, "FFFF5B0000000000")
        ingest.ingest(
            UUID.randomUUID(), s, "esp32-node-1",
            listOf(frame(t0 - 10, 3000.0, 50.0), foraDeFaixa),
        )

        val r = assertNotNull(porSinal(s.value)["rpm"])
        // O ponto continua GRAVADO e CONTADO: saber que o sensor caiu e informacao.
        assertEquals(2L, (r["count"] as Number).toLong())
        assertEquals(1L, (r["invalidCount"] as Number).toLong())
        // Mas ele nao entra no maximo. "RPM maximo 16.383" para um motor que nao
        // passa de 8.000 nao e informacao extra, e informacao errada (V7).
        assertEquals(
            3000.0, (r["max"] as Number).toDouble(),
            "o maximo deveria ser a unica leitura valida, nao o espeto do sensor",
        )
        assertEquals(3000.0, (r["avg"] as Number).toDouble(), "a media so pondera o que e valido")
    }

    @Test
    fun `a unidade vem do DBC, nao do banco`() {
        val s = sessao("e")
        val t0 = Instant.parse("2026-08-24T12:00:00Z").toEpochMilli()
        ingest.ingest(UUID.randomUUID(), s, "esp32-node-1", listOf(frame(t0, 3000.0, 55.0)))

        val sinais = porSinal(s.value)
        assertEquals("rpm", assertNotNull(sinais["rpm"])["unit"])
        assertEquals("degC", assertNotNull(sinais["temp"])["unit"])
    }

    @Test
    fun `sessao inexistente vira 404 em Problem Details`() {
        mockMvc.get("/api/v1/sessions/2026-01-01-nao-existe/summary").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("\$.type") { value("https://baja.unb.br/errors/session-not-found") }
            jsonPath("\$.retryable") { value(false) }
        }
    }

    @Test
    fun `sessao existente mas sem dado devolve lista vazia, nao 404`() {
        val s = sessao("f")
        jdbc.update("INSERT INTO session (id) VALUES (?) ON CONFLICT DO NOTHING", s.value)

        mockMvc.get("/api/v1/sessions/${s.value}/summary").andExpect {
            status { isOk() }
            jsonPath("\$.length()") { value(0) }
        }
    }
}
