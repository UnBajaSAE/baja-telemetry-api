package br.unb.baja.telemetry.domain.can

import br.unb.baja.telemetry.domain.can.dbc.DbcLoader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * O oraculo destes testes NAO sou eu.
 *
 * Os bytes esperados foram produzidos pelo `cantools` lendo
 * `contracts/can/unbaja.dbc` de verdade, na Fase 0. Do nosso lado, os sinais
 * tambem saem desse arquivo, pelo `DbcParser`. Entao o que este teste compara
 * sao duas leituras independentes do MESMO arquivo -- a nossa e a de uma
 * implementacao madura. Divergencia em qualquer uma das duas aparece aqui.
 *
 * Reproduzir:
 *   pipx run --spec cantools python -c "import cantools; \
 *     db = cantools.database.load_file('contracts/can/unbaja.dbc'); \
 *     print(db.encode_message(0x100, {'rpm': 4000.0, 'temp': 51}).hex().upper())"
 */
class FrameEncoderTest {

    // Os sinais vem do arquivo de verdade, lidos pelo parser -- nao de uma copia
    // escrita a mao. Assim este teste cobre parser e codificador juntos, e uma
    // divergencia entre o codigo e o .dbc aparece aqui.
    private val db = DbcLoader.doClasspath()
    private fun sinal(canId: Int, nome: String) = assertNotNull(db.signal(canId, nome))

    private val rpm get() = sinal(0x100, "rpm")
    private val temp get() = sinal(0x100, "temp")

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    @Test
    fun `MOTOR bate byte a byte com o cantools`() {
        val payload = FrameEncoder.encode(
            8,
            mapOf(sinal(0x100, "rpm") to 4000.0, sinal(0x100, "temp") to 51.0),
        )
        assertEquals("3E805B0000000000", hex(payload))
    }

    @Test
    fun `DINAMICA bate -- sinal de 12 bits cruzando fronteira de byte`() {
        val payload = FrameEncoder.encode(
            8,
            mapOf(sinal(0x200, "speed") to 62.5, sinal(0x200, "gear") to 3.0),
        )
        assertEquals("7132000000000000", hex(payload))
    }

    @Test
    fun `GPS bate -- signed em complemento de dois, coordenadas de Brasilia`() {
        val payload = FrameEncoder.encode(
            8,
            mapOf(sinal(0x300, "lat") to -15.7942, sinal(0x300, "lon") to -47.8822),
        )
        assertEquals("F695FF10E375C190", hex(payload))
    }

    @Test
    fun `a escala move a precisao para onde o fenomeno acontece`() {
        // 0x3E80 = 16000 cru; com escala 0.25 isso e 4000 rpm (docs/01)
        assertEquals(16000L, rpm.rawOf(4000.0))
        assertEquals(4000.0, rpm.physicalOf(16000L))
    }

    @Test
    fun `o offset cobre temperatura negativa sem gastar bit de sinal`() {
        assertEquals(0L, temp.rawOf(-40.0))
        assertEquals(40L, temp.rawOf(0.0))
        assertEquals(255L, temp.rawOf(215.0))
    }

    @Test
    fun `valor acima do representavel e limitado, nao transborda`() {
        // 16 bits com escala 0.25 representam ate 16383.75 rpm
        val cru = rpm.rawOf(99_999.0)
        assertEquals(65535L, cru, "deveria saturar no maximo de 16 bits, nao dar a volta")
    }

    @Test
    fun `a tolerancia da ida e volta sai da escala, nao de um numero inventado`() {
        // 4000,10 rpm nao existe no barramento: com escala 0,25 ele vira 4000,00.
        val fisico = 4000.10
        val volta = rpm.physicalOf(rpm.rawOf(fisico))
        assertTrue(
            kotlin.math.abs(volta - fisico) <= rpm.quantizationTolerance,
            "erro de $volta vs $fisico passou da tolerancia de quantizacao",
        )
        assertEquals(0.125, rpm.quantizationTolerance)
    }
}
