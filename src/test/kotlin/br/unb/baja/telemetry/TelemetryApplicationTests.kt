package br.unb.baja.telemetry

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * Checkpoint 1.1 — o esqueleto sobe e responde.
 *
 * Verifica o criterio de aceite da fase: a aplicacao inicia e o Actuator
 * reporta UP. Cobre tambem o "contextLoads" gerado pelo Initializr, porque
 * um contexto que nao sobe faz este teste falhar antes de chegar no HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TelemetryApplicationTests(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `a aplicacao sobe e o actuator reporta UP`() {
        mockMvc.get("/actuator/health")
            .andExpect {
                status { isOk() }
                jsonPath("\$.status") { value("UP") }
            }
    }
}
