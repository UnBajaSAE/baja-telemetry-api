package br.unb.baja.telemetry.domain.can

import kotlin.math.roundToLong

enum class ByteOrder { BIG, LITTLE }

/**
 * A definicao de um sinal dentro de um frame: o conteudo de uma linha `SG_` do DBC.
 *
 * Na Fase 2 o parser de DBC passa a produzir estes objetos lendo
 * `contracts/can/unbaja.dbc`. Ate la eles sao montados a mao, espelhando o mesmo
 * arquivo -- ver `SinaisDoBaja`.
 */
data class SignalDefinition(
    val name: String,
    /**
     * Bit inicial na numeracao do DBC. O significado MUDA com a endianness:
     * em BIG aponta o bit MAIS significativo do sinal, em LITTLE o MENOS.
     * Errar isso nao gera excecao -- gera numero plausivel e errado (docs/05).
     */
    val startBit: Int,
    val bitLength: Int,
    val byteOrder: ByteOrder,
    val signed: Boolean,
    val scale: Double,
    val offset: Double,
    val min: Double,
    val max: Double,
    val unit: String,
) {
    init {
        require(bitLength in 1..64) { "$name: largura $bitLength fora de 1..64" }
        require(scale != 0.0) { "$name: escala nao pode ser zero" }
    }

    /** Maior erro possivel da ida e volta, por causa da quantizacao (docs/09). */
    val quantizationTolerance: Double get() = kotlin.math.abs(scale) / 2.0

    /**
     * As posicoes de bit que este sinal ocupa, do bit MAIS significativo do sinal
     * para o menos.
     *
     * Posicao global = indice_do_byte * 8 + indice_dentro_do_byte, com o bit 0
     * sendo o menos significativo de cada byte.
     */
    fun bitPositionsMsbFirst(): List<Int> = when (byteOrder) {
        // LITTLE: startBit e o LSB, e o sinal cresce em posicao global.
        ByteOrder.LITTLE -> (bitLength - 1 downTo 0).map { startBit + it }

        // BIG: startBit e o MSB. Decresce dentro do byte e, ao esgotar,
        // salta para o bit 7 do byte seguinte.
        ByteOrder.BIG -> buildList {
            var byteIdx = startBit / 8
            var bitIdx = startBit % 8
            repeat(bitLength) {
                add(byteIdx * 8 + bitIdx)
                if (--bitIdx < 0) { bitIdx = 7; byteIdx++ }
            }
        }
    }

    /** `fisico = (cru * escala) + offset`, invertida. */
    fun rawOf(physical: Double): Long {
        val cru = ((physical - offset) / scale).roundToLong()
        val limite = if (signed) {
            -(1L shl (bitLength - 1))..((1L shl (bitLength - 1)) - 1)
        } else {
            0L..((1L shl bitLength) - 1)
        }
        return cru.coerceIn(limite)
    }

    /** `fisico = (cru * escala) + offset`. */
    fun physicalOf(raw: Long): Double = raw * scale + offset

    fun isInRange(physical: Double): Boolean = physical in min..max
}
