package br.unb.baja.telemetry.adapter.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import br.unb.baja.telemetry.support.TestcontainersConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * Contrato do POST /api/v1/ingest (docs/03 e docs/08).
 *
 * O que se verifica nos erros NAO e o codigo HTTP -- e o campo `retryable`.
 * Um 503 sem `retryable` faz o firmware apagar um buffer que deveria ter
 * guardado, e o dado some do cartao sem nunca ter chegado ao servidor.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class IngestControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    private fun postLote(corpo: String) =
        mockMvc.post("/api/v1/ingest") {
            contentType = MediaType.APPLICATION_JSON
            content = corpo
        }

    @Test
    fun `aceita o lote de exemplo do docs-03`() {
        postLote(
            """
            {
              "batchId": "550e8400-e29b-41d4-a716-446655440000",
              "deviceId": "esp32-node-1",
              "sessionId": "2026-08-24-teste-suspensao",
              "frames": [
                { "t": 1756041600123, "id": 256, "data": "3E805B0000000000" },
                { "t": 1756041600133, "id": 256, "data": "3E925C0000000000" }
              ]
            }
            """,
        ).andExpect {
            status { isOk() }
            jsonPath("\$.batchId") { value("550e8400-e29b-41d4-a716-446655440000") }
            jsonPath("\$.framesReceived") { value(2) }
            jsonPath("\$.framesRejected") { value(0) }
            jsonPath("\$.duplicate") { value(false) }
            // Checkpoint 1.3 ainda nao persiste. Vira 2 na Fase 2.
            jsonPath("\$.framesStored") { value(0) }
        }
    }

    @Test
    fun `um frame ruim nao derruba o lote -- ADR-010`() {
        postLote(
            """
            {
              "batchId": "550e8400-e29b-41d4-a716-446655440001",
              "deviceId": "esp32-node-1",
              "sessionId": "2026-08-24-teste-suspensao",
              "frames": [
                { "t": 1756041600123, "id": 256, "data": "3E805B0000000000" },
                { "t": 1756041600133, "id": 256, "data": "3E805B000000000" },
                { "t": 1756041600143, "id": 256, "data": "A1B2" }
              ]
            }
            """,
        ).andExpect {
            status { isOk() }
            jsonPath("\$.framesReceived") { value(3) }
            jsonPath("\$.framesRejected") { value(1) }
            jsonPath("\$.rejections[0].index") { value(1) }
            jsonPath("\$.rejections[0].reason") { value("malformed-frame") }
        }
    }

    @Test
    fun `JSON ilegivel vira 400 no formato Problem Details`() {
        postLote("{ isto nao e json }").andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("\$.type") { value("https://baja.unb.br/errors/malformed-body") }
            jsonPath("\$.retryable") { value(false) }
        }
    }

    @Test
    fun `campo obrigatorio ausente vira 400`() {
        postLote(
            """
            { "deviceId": "esp32-node-1", "sessionId": "2026-08-24-teste",
              "frames": [ { "t": 1, "id": 1, "data": "A1" } ] }
            """,
        ).andExpect {
            status { isBadRequest() }
            jsonPath("\$.retryable") { value(false) }
        }
    }

    @Test
    fun `lote acima de 5000 frames vira 413, nao retentavel`() {
        val frames = (1..5001).joinToString(",") {
            """{ "t": $it, "id": 256, "data": "A1B2" }"""
        }
        postLote(
            """
            { "batchId": "550e8400-e29b-41d4-a716-446655440002",
              "deviceId": "esp32-node-1", "sessionId": "2026-08-24-teste",
              "frames": [ $frames ] }
            """,
        ).andExpect {
            status { isEqualTo(413) }
            jsonPath("\$.type") { value("https://baja.unb.br/errors/batch-too-large") }
            // Refatiar resolve; retentar igual nao. O firmware precisa saber disso.
            jsonPath("\$.retryable") { value(false) }
        }
    }

    @Test
    fun `sessionId fora do formato vira 422`() {
        postLote(
            """
            { "batchId": "550e8400-e29b-41d4-a716-446655440003",
              "deviceId": "esp32-node-1", "sessionId": "teste de suspensao",
              "frames": [ { "t": 1, "id": 1, "data": "A1" } ] }
            """,
        ).andExpect {
            status { isEqualTo(422) }
            jsonPath("\$.type") { value("https://baja.unb.br/errors/invalid-session-id") }
            jsonPath("\$.retryable") { value(false) }
        }
    }
}
