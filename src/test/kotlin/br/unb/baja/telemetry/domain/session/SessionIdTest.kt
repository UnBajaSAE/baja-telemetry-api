package br.unb.baja.telemetry.domain.session

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionIdTest {

    @Test
    fun `aceita o formato do ADR-007`() {
        val id = assertNotNull(SessionId.parseOrNull("2026-08-24-teste-suspensao"))
        assertEquals("2026-08-24-teste-suspensao", id.value)
    }

    @Test
    fun `aceita slug de uma palavra so`() {
        assertNotNull(SessionId.parseOrNull("2026-08-24-enduro"))
    }

    @Test
    fun `rejeita sem a data na frente`() {
        assertNull(SessionId.parseOrNull("teste-suspensao"))
    }

    @Test
    fun `rejeita maiuscula, espaco e acento`() {
        assertNull(SessionId.parseOrNull("2026-08-24-Teste"))
        assertNull(SessionId.parseOrNull("2026-08-24-teste suspensao"))
        assertNull(SessionId.parseOrNull("2026-08-24-suspensao-traseira-ç"))
    }

    @Test
    fun `rejeita sem slug depois da data`() {
        assertNull(SessionId.parseOrNull("2026-08-24"))
        assertNull(SessionId.parseOrNull("2026-08-24-"))
    }

    @Test
    fun `rejeita id absurdamente longo`() {
        assertNull(SessionId.parseOrNull("2026-08-24-" + "a".repeat(200)))
    }
}
