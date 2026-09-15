package br.unb.baja.telemetry.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BucketTest {

    @Test
    fun `aceita segundos, minutos e horas`() {
        assertEquals(1L, assertNotNull(Bucket.parseOrNull("1s")).seconds)
        assertEquals(30L, assertNotNull(Bucket.parseOrNull("30s")).seconds)
        assertEquals(300L, assertNotNull(Bucket.parseOrNull("5m")).seconds)
        assertEquals(3600L, assertNotNull(Bucket.parseOrNull("1h")).seconds)
    }

    @Test
    fun `recusa janela menor que 1 segundo -- o agregado nao teria o que responder`() {
        assertNull(Bucket.parseOrNull("0s"))
        assertNull(Bucket.parseOrNull("500ms"))
        assertNull(Bucket.parseOrNull("0.5s"))
    }

    @Test
    fun `recusa formato desconhecido em vez de adivinhar`() {
        for (ruim in listOf("", "s", "abc", "1d", "-5s", "1 semana", "1,5m")) {
            assertNull(Bucket.parseOrNull(ruim), "deveria recusar '$ruim'")
        }
    }

    @Test
    fun `normaliza na volta -- 60s vira 1m`() {
        assertEquals("1m", Bucket.parseOrNull("60s").toString())
        assertEquals("1h", Bucket.parseOrNull("3600s").toString())
        assertEquals("90s", Bucket.parseOrNull("90s").toString())
        assertEquals("2h", Bucket.parseOrNull("120m").toString())
    }
}
