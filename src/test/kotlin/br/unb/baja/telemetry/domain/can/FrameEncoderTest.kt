package br.unb.baja.telemetry.domain.can

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O oraculo destes testes NAO sou eu.
 *
 * Os bytes esperados foram produzidos pelo `cantools` lendo
 * `contracts/can/unbaja.dbc` de verdade, na Fase 0. Se o nosso codificador
 * divergir do arquivo, estes testes quebram -- e e assim que a duplicacao
 * temporaria do `SinaisDoBaja` deixa de ser perigosa.
 *
 * Reproduzir:
 *   pipx run --spec cantools python -c "import cantools; \
 *     db = cantools.database.load_file('contracts/can/unbaja.dbc'); \
 *     print(db.encode_message(0x100, {'rpm': 4000.0, 'temp': 51}).hex().upper())"
 */
class FrameEncoderTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    @Test
    fun `MOTOR bate byte a byte com o cantools`() {
        val payload = FrameEncoder.encode(
            8,
            mapOf(SinaisDoBaja.RPM to 4000.0, SinaisDoBaja.TEMP to 51.0),
        )
        assertEquals("3E805B0000000000", hex(payload))
    }

    @Test
    fun `DINAMICA bate -- sinal de 12 bits cruzando fronteira de byte`() {
        val payload = FrameEncoder.encode(
            8,
            mapOf(SinaisDoBaja.SPEED to 62.5, SinaisDoBaja.GEAR to 3.0),
        )
        assertEquals("7132000000000000", hex(payload))
    }

    @Test
    fun `GPS bate -- signed em complemento de dois, coordenadas de Brasilia`() {
        val payload = FrameEncoder.encode(
            8,
            mapOf(SinaisDoBaja.LAT to -15.7942, SinaisDoBaja.LON to -47.8822),
        )
        assertEquals("F695FF10E375C190", hex(payload))
    }

    @Test
    fun `a escala move a precisao para onde o fenomeno acontece`() {
        // 0x3E80 = 16000 cru; com escala 0.25 isso e 4000 rpm (docs/01)
        assertEquals(16000L, SinaisDoBaja.RPM.rawOf(4000.0))
        assertEquals(4000.0, SinaisDoBaja.RPM.physicalOf(16000L))
    }

    @Test
    fun `o offset cobre temperatura negativa sem gastar bit de sinal`() {
        assertEquals(0L, SinaisDoBaja.TEMP.rawOf(-40.0))
        assertEquals(40L, SinaisDoBaja.TEMP.rawOf(0.0))
        assertEquals(255L, SinaisDoBaja.TEMP.rawOf(215.0))
    }

    @Test
    fun `valor acima do representavel e limitado, nao transborda`() {
        // 16 bits com escala 0.25 representam ate 16383.75 rpm
        val cru = SinaisDoBaja.RPM.rawOf(99_999.0)
        assertEquals(65535L, cru, "deveria saturar no maximo de 16 bits, nao dar a volta")
    }

    @Test
    fun `a tolerancia da ida e volta sai da escala, nao de um numero inventado`() {
        // 4000,10 rpm nao existe no barramento: com escala 0,25 ele vira 4000,00.
        val fisico = 4000.10
        val volta = SinaisDoBaja.RPM.physicalOf(SinaisDoBaja.RPM.rawOf(fisico))
        assertTrue(
            kotlin.math.abs(volta - fisico) <= SinaisDoBaja.RPM.quantizationTolerance,
            "erro de $volta vs $fisico passou da tolerancia de quantizacao",
        )
        assertEquals(0.125, SinaisDoBaja.RPM.quantizationTolerance)
    }
}
