package br.unb.baja.telemetry.domain.can

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Testes do dominio: rodam em milissegundos, sem Spring, sem banco, sem porta.
 * E o que o ADR-009 comprou ao manter `domain` livre de framework.
 */
class CanFrameTest {

    private val T = 1756041600123L

    @Test
    fun `aceita o frame de exemplo do docs-01`() {
        val r = CanFrame.parse(T, 0x100, "3E805B0000000000")
        val frame = assertIs<FrameParse.Accepted>(r).frame

        assertEquals(0x100, frame.canId)
        assertEquals(8, frame.payload.size)
        // 0x3E80 = 16000, que com escala 0.25 vira 4000 rpm na Fase 2
        assertEquals(0x3E.toByte(), frame.payload[0])
        assertEquals(0x80.toByte(), frame.payload[1])
    }

    @Test
    fun `aceita payload curto -- DLC menor que 8 e valido`() {
        val frame = assertIs<FrameParse.Accepted>(CanFrame.parse(T, 1, "A1B2")).frame
        assertEquals(2, frame.payload.size)
    }

    @Test
    fun `rejeita hex com numero impar de digitos`() {
        // 15 digitos: nao fecha em bytes inteiros. Um digito a mais seria um
        // payload de 8 bytes perfeitamente valido -- a diferenca e um caractere.
        val hex = "3E805B000000000"
        assertEquals(1, hex.length % 2, "o dado deste teste precisa ter tamanho impar")

        val r = assertIs<FrameParse.Rejected>(CanFrame.parse(T, 1, hex))
        assertEquals(RejectionReason.MALFORMED_FRAME, r.reason)
    }

    @Test
    fun `rejeita caractere nao-hexadecimal`() {
        val r = assertIs<FrameParse.Rejected>(CanFrame.parse(T, 1, "3E8ZZZ00"))
        assertEquals(RejectionReason.MALFORMED_FRAME, r.reason)
    }

    @Test
    fun `rejeita payload acima de 8 bytes`() {
        val r = assertIs<FrameParse.Rejected>(CanFrame.parse(T, 1, "00".repeat(9)))
        assertEquals(RejectionReason.MALFORMED_FRAME, r.reason)
    }

    @Test
    fun `rejeita payload vazio`() {
        assertIs<FrameParse.Rejected>(CanFrame.parse(T, 1, ""))
    }

    @Test
    fun `rejeita canId acima de 11 bits`() {
        val r = assertIs<FrameParse.Rejected>(CanFrame.parse(T, 0x800, "A1"))
        assertEquals(RejectionReason.CAN_ID_OUT_OF_RANGE, r.reason)
    }

    @Test
    fun `aceita o canId maximo de 11 bits`() {
        assertIs<FrameParse.Accepted>(CanFrame.parse(T, 0x7FF, "A1"))
    }

    @Test
    fun `dois frames com o mesmo conteudo sao iguais`() {
        // ByteArray compara por identidade em Kotlin; sem equals proprio isto falharia
        val a = assertIs<FrameParse.Accepted>(CanFrame.parse(T, 1, "A1B2")).frame
        val b = assertIs<FrameParse.Accepted>(CanFrame.parse(T, 1, "A1B2")).frame
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
