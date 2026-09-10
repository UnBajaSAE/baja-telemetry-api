package br.unb.baja.telemetry.domain.can

import br.unb.baja.telemetry.domain.can.dbc.DbcLoader
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * O coracao do sistema, e o unico lugar onde um bug nao gera excecao -- gera um
 * numero plausivel e errado, que e gravado e vira decisao de engenharia.
 *
 * Roda sem Spring, sem banco, sem porta: e o que o ADR-009 comprou, e o que
 * permite rodar centenas de casos aleatorios a cada salvamento de arquivo.
 */
class FrameDecoderTest {

    private val db = DbcLoader.doClasspath()
    private fun sinal(canId: Int, nome: String) = assertNotNull(db.signal(canId, nome))
    private val todosOsSinais = db.messages.flatMap { m -> m.signals.map { m to it } }

    // ------------------------------------------------ propriedade 1: os bits

    /**
     * `encode(decode(bits)) == bits` -- igualdade EXATA, em aritmetica inteira.
     *
     * Cobre todo o espaco de entrada de cada sinal, inclusive os extremos que
     * ninguem pensa em testar a mao: tudo zero, tudo um, so o bit de sinal.
     */
    @Test
    fun `ida e volta dos bits e exata, para qualquer padrao`() = runBlocking {
        for ((msg, s) in todosOsSinais) {
            val maximo = if (s.bitLength >= 63) Long.MAX_VALUE else (1L shl s.bitLength) - 1
            checkAll(200, Arb.long(0L..maximo)) { bits ->
                val payload = FrameEncoder.encodeRaw(msg.dlc, mapOf(s to bits))
                val volta = FrameDecoder.rawBits(s, payload)
                assertEquals(
                    bits, volta,
                    "${s.name}: ${bits.toString(2).padStart(s.bitLength, '0')} nao sobreviveu " +
                        "a ida e volta (payload ${payload.joinToString("") { "%02X".format(it) }})",
                )
            }
        }
    }

    @Test
    fun `os extremos de cada sinal sobrevivem`() {
        for ((msg, s) in todosOsSinais) {
            val maximo = (1L shl s.bitLength) - 1
            for (bits in listOf(0L, 1L, maximo, maximo / 2, 1L shl (s.bitLength - 1))) {
                val payload = FrameEncoder.encodeRaw(msg.dlc, mapOf(s to bits))
                assertEquals(bits, FrameDecoder.rawBits(s, payload), "${s.name} com bits=$bits")
            }
        }
    }

    // ------------------------------------------- propriedade 2: a quantizacao

    /**
     * `|decode(encode(x)) - x| <= escala/2`.
     *
     * NAO pode usar igualdade: com escala 0,25 o valor 4.000,10 nao existe no
     * barramento e vira 4.000,00 ao codificar. Igualdade daria um teste que
     * falha sempre; tolerancia inventada daria um que passa sempre. A tolerancia
     * certa sai da escala do proprio sinal, lida do DBC.
     */
    @Test
    fun `ida e volta do valor fisico respeita a tolerancia de quantizacao`() = runBlocking {
        for ((msg, s) in todosOsSinais) {
            checkAll(200, Arb.double(s.min, s.max).filter { it.isFinite() }) { fisico ->
                val payload = FrameEncoder.encode(msg.dlc, mapOf(s to fisico))
                val volta = FrameDecoder.decode(s, payload)
                assertTrue(
                    abs(volta.value - fisico) <= s.quantizationTolerance,
                    "${s.name}: $fisico voltou como ${volta.value}, erro " +
                        "${abs(volta.value - fisico)} acima da tolerancia ${s.quantizationTolerance}",
                )
            }
        }
    }

    // ------------------------------------------------- as tres armadilhas

    @Test
    fun `armadilha 1 -- endianness -- o payload do docs-01 decodifica certo`() {
        val motor = assertNotNull(db.message(0x100))
        val payload = "3E805B0000000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val v = FrameDecoder.decode(motor, payload).associateBy { it.name }
        // Endianness trocada daria 8.207,5 rpm -- sem erro nenhum.
        assertEquals(4000.0, assertNotNull(v["rpm"]).value)
        assertEquals(51.0, assertNotNull(v["temp"]).value)
        assertTrue(v.values.all { it.valid })
    }

    @Test
    fun `armadilha 2 -- sinal de 12 bits cruzando fronteira de byte`() {
        val dinamica = assertNotNull(db.message(0x200))
        // Bytes que o cantools produziu para speed=62.5 e gear=3
        val payload = "7132000000000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val v = FrameDecoder.decode(dinamica, payload).associateBy { it.name }
        assertEquals(62.5, assertNotNull(v["speed"]).value)
        assertEquals(3.0, assertNotNull(v["gear"]).value)
    }

    @Test
    fun `armadilha 3 -- signed -- latitude do hemisferio sul e negativa`() {
        val gps = assertNotNull(db.message(0x300))
        val payload = "F695FF10E375C190".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val v = FrameDecoder.decode(gps, payload).associateBy { it.name }
        val lat = assertNotNull(v["lat"]).value
        val lon = assertNotNull(v["lon"]).value

        // Tratado como unsigned, isto viraria +214,7 graus -- que nem existe.
        assertTrue(lat < 0, "latitude de Brasilia tem que ser negativa, veio $lat")
        assertTrue(lon < 0, "longitude de Brasilia tem que ser negativa, veio $lon")
        assertTrue(abs(lat - (-15.7942)) < 1e-6, "lat=$lat")
        assertTrue(abs(lon - (-47.8822)) < 1e-6, "lon=$lon")
    }

    @Test
    fun `o bit de sinal e lido da largura DECLARADA, nao de 8 ou 16 ou 32`() = runBlocking {
        // Um sinal signed de 12 bits: o bit 11 e o de sinal, nao o 15 nem o 31.
        val s = SignalDefinition("teste", 0, 12, ByteOrder.LITTLE, true, 1.0, 0.0, -2048.0, 2047.0, "")
        checkAll(200, Arb.long(-2048L..2047L)) { esperado ->
            val payload = FrameEncoder.encodeRaw(2, mapOf(s to esperado))
            assertEquals(esperado.toDouble(), FrameDecoder.decode(s, payload).value)
        }
    }

    // ------------------------------------------------ faixa e payload curto

    @Test
    fun `fora de faixa e MARCADO invalido, nao descartado e nao vira excecao`() {
        val rpm = sinal(0x100, "rpm")
        // Sensor desconectado manda 0xFF em tudo: 65535 * 0,25 = 16.383,75 rpm
        val payload = ByteArray(8) { 0xFF.toByte() }

        val v = FrameDecoder.decode(rpm, payload)
        assertFalse(v.valid, "16.383 rpm esta fora da faixa [0, 8000] do DBC")
        assertEquals(16383.75, v.value, "o valor tem que ser PRESERVADO, nao zerado")
        assertNotNull(v.invalidReason)
        assertTrue("faixa" in v.invalidReason!!)
    }

    @Test
    fun `valor dentro da faixa e valido e nao carrega motivo`() {
        val v = FrameDecoder.decode(
            sinal(0x100, "rpm"),
            FrameEncoder.encode(8, mapOf(sinal(0x100, "rpm") to 4000.0)),
        )
        assertTrue(v.valid)
        assertEquals(null, v.invalidReason)
    }

    @Test
    fun `payload curto demais vira invalido explicito, nao zero`() {
        // Zero e um valor plausivel: se um sinal ilegivel virasse 0, passaria
        // despercebido no grafico como "carro parado".
        val temp = sinal(0x100, "temp") // ocupa o byte 2
        val v = FrameDecoder.decode(temp, ByteArray(2))

        assertFalse(v.valid)
        assertTrue(v.value.isNaN(), "nao pode virar 0.0 -- seria confundido com leitura real")
        assertTrue("payload" in v.invalidReason!!, v.invalidReason!!)
    }

    @Test
    fun `decodifica a mensagem inteira, todos os sinais de uma vez`() {
        val motor = assertNotNull(db.message(0x100))
        val valores = FrameDecoder.decode(motor, ByteArray(8))
        assertEquals(listOf("rpm", "temp"), valores.map { it.name })
        assertEquals(listOf("rpm", "degC"), valores.map { it.unit })
    }
}
