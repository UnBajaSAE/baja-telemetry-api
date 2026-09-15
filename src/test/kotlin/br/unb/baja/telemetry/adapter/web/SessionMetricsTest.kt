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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checkpoint 3.3 -- a serie temporal por janela.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class SessionMetricsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val ingest: IngestService,
    @Autowired private val json: ObjectMapper,
    @Autowired private val dbc: DbcDatabase,
) {
    private val marca = UUID.randomUUID().toString().take(8)
    private val motor = dbc.message(0x100)!!
    private val rpm = dbc.signal(0x100, "rpm")!!
    private val temp = dbc.signal(0x100, "temp")!!
    private val t0 = Instant.parse("2026-08-24T09:00:00Z")

    private fun frame(ms: Long, valorRpm: Double) = IncomingFrame(
        ms, 0x100,
        FrameEncoder.encode(motor.dlc, mapOf(rpm to valorRpm, temp to 60.0))
            .joinToString("") { "%02X".format(it) },
    )

    @Suppress("UNCHECKED_CAST")
    private fun metricas(id: String, query: String): Map<String, Any?> {
        val corpo = mockMvc.get("/api/v1/sessions/$id/metrics?$query")
            .andReturn().response.contentAsString
        return json.readValue(corpo, Map::class.java) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun pontos(m: Map<String, Any?>) = m["points"] as List<Map<String, Any?>>

    /** Tres segundos: 10 leituras a 1000, 10 a 2000, 10 a 3000 rpm. */
    private fun sessaoDeTresSegundos(sufixo: String): SessionId {
        val s = SessionId.parseOrNull("2026-08-24-metricas-$marca-$sufixo")!!
        val frames = buildList {
            listOf(1000.0, 2000.0, 3000.0).forEachIndexed { seg, valor ->
                repeat(10) { add(frame(t0.toEpochMilli() + seg * 1000L + it * 50L, valor)) }
            }
        }
        ingest.ingest(UUID.randomUUID(), s, "esp32-node-1", frames)
        return s
    }

    @Test
    fun `janela de 1s devolve um ponto por segundo, com os valores certos`() {
        val s = sessaoDeTresSegundos("a")
        val m = metricas(s.value, "signal=rpm&bucket=1s")
        val p = pontos(m)

        assertEquals(3, p.size, "tres segundos de dado, tres pontos")
        assertEquals(listOf(1000.0, 2000.0, 3000.0), p.map { (it["avg"] as Number).toDouble() })
        assertEquals(listOf(10L, 10L, 10L), p.map { (it["count"] as Number).toLong() })
        assertEquals("1s", m["bucket"])
        assertEquals("rpm", m["unit"])
    }

    @Test
    fun `janela maior REAGRUPA as de 1s, com media ponderada`() {
        val s = sessaoDeTresSegundos("b")
        val m = metricas(s.value, "signal=rpm&bucket=1m")
        val p = pontos(m)

        assertEquals(1, p.size, "os tres segundos cabem numa janela de 1 minuto")
        // (10*1000 + 10*2000 + 10*3000) / 30 = 2000
        assertEquals(2000.0, (p[0]["avg"] as Number).toDouble())
        assertEquals(1000.0, (p[0]["min"] as Number).toDouble())
        assertEquals(3000.0, (p[0]["max"] as Number).toDouble())
        assertEquals(30L, (p[0]["count"] as Number).toLong())
    }

    @Test
    fun `from e to recortam o intervalo`() {
        val s = sessaoDeTresSegundos("c")
        val m = metricas(
            s.value,
            "signal=rpm&bucket=1s&from=${t0.plusSeconds(1)}&to=${t0.plusSeconds(2)}",
        )
        val p = pontos(m)
        assertEquals(1, p.size)
        assertEquals(2000.0, (p[0]["avg"] as Number).toDouble())
    }

    @Test
    fun `sem from e to, cobre a sessao inteira`() {
        val s = sessaoDeTresSegundos("d")
        val m = metricas(s.value, "signal=rpm")
        assertEquals(3, pontos(m).size)
        assertTrue((m["from"] as String).startsWith("2026-08-24T09:00:00"))
    }

    @Test
    fun `leitura invalida nao entra na media, mas e contada no ponto`() {
        val s = SessionId.parseOrNull("2026-08-24-metricas-$marca-e")!!
        ingest.ingest(
            UUID.randomUUID(), s, "esp32-node-1",
            listOf(
                frame(t0.toEpochMilli(), 3000.0),
                IncomingFrame(t0.toEpochMilli() + 100, 0x100, "FFFF5B0000000000"), // 16.383 rpm
            ),
        )
        val p = pontos(metricas(s.value, "signal=rpm&bucket=1s"))
        assertEquals(1, p.size)
        assertEquals(3000.0, (p[0]["avg"] as Number).toDouble(), "o espeto do sensor nao pondera")
        assertEquals(3000.0, (p[0]["max"] as Number).toDouble())
        assertEquals(2L, (p[0]["count"] as Number).toLong())
        assertEquals(1L, (p[0]["invalidCount"] as Number).toLong())
    }

    @Test
    fun `janela onde TUDO foi invalido devolve estatisticas nulas, nao zero`() {
        val s = SessionId.parseOrNull("2026-08-24-metricas-$marca-f")!!
        ingest.ingest(
            UUID.randomUUID(), s, "esp32-node-1",
            listOf(IncomingFrame(t0.toEpochMilli(), 0x100, "FFFF5B0000000000")),
        )
        val p = pontos(metricas(s.value, "signal=rpm&bucket=1s"))
        assertEquals(1, p.size)
        // Zero seria confundido com "motor desligado". Nulo diz "nao sei".
        assertNull(p[0]["avg"])
        assertNull(p[0]["max"])
        assertEquals(1L, (p[0]["invalidCount"] as Number).toLong())
    }

    // ------------------------------------------------------------ recusas

    @Test
    fun `janela menor que 1s vira 422 -- nao ha resposta possivel`() {
        val s = sessaoDeTresSegundos("g")
        mockMvc.get("/api/v1/sessions/${s.value}/metrics?signal=rpm&bucket=100ms").andExpect {
            status { isEqualTo(422) }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("\$.type") { value("https://baja.unb.br/errors/invalid-bucket") }
            jsonPath("\$.retryable") { value(false) }
        }
    }

    @Test
    fun `sinal com erro de digitacao vira 422 e diz quais existem`() {
        val s = sessaoDeTresSegundos("h")
        mockMvc.get("/api/v1/sessions/${s.value}/metrics?signal=rmp").andExpect {
            status { isEqualTo(422) }
            jsonPath("\$.type") { value("https://baja.unb.br/errors/unknown-signal") }
            jsonPath("\$.detail") { value(org.hamcrest.Matchers.containsString("rpm")) }
        }
    }

    @Test
    fun `intervalo grande demais para a janela vira 422, sugerindo janela maior`() {
        val s = sessaoDeTresSegundos("i")
        mockMvc.get(
            "/api/v1/sessions/${s.value}/metrics" +
                "?signal=rpm&bucket=1s&from=2020-01-01T00:00:00Z&to=2026-01-01T00:00:00Z",
        ).andExpect {
            status { isEqualTo(422) }
            jsonPath("\$.type") { value("https://baja.unb.br/errors/too-many-points") }
            jsonPath("\$.detail") { value(org.hamcrest.Matchers.containsString("bucket=")) }
        }
    }

    @Test
    fun `sessao inexistente vira 404`() {
        mockMvc.get("/api/v1/sessions/2026-01-01-nao-existe/metrics?signal=rpm").andExpect {
            status { isNotFound() }
            jsonPath("\$.type") { value("https://baja.unb.br/errors/session-not-found") }
        }
    }
}
