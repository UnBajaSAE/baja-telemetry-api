package br.unb.baja.telemetry.domain.can

/**
 * Monta o payload de um frame a partir de valores fisicos.
 *
 * E a inversa do decodificador da Fase 2, e existe agora por dois motivos: o
 * gerador sintetico precisa dela (ADR-005), e o teste de propriedade
 * `decode(encode(x)) == x` vai precisar tambem (docs/09).
 */
object FrameEncoder {

    /**
     * @param dlc quantos bytes o frame carrega
     * @param valores valor fisico por sinal
     */
    fun encode(dlc: Int, valores: Map<SignalDefinition, Double>): ByteArray {
        require(dlc in 1..CanFrame.MAX_PAYLOAD_BYTES) { "DLC $dlc fora de 1..8" }
        val bytes = ByteArray(dlc)

        for ((sinal, fisico) in valores) {
            val cru = sinal.rawOf(fisico)
            // Complemento de dois: um valor negativo vira o padrao de bits sem sinal
            // correspondente, dentro da largura declarada.
            val bits = if (cru < 0) cru + (1L shl sinal.bitLength) else cru

            sinal.bitPositionsMsbFirst().forEachIndexed { i, posicao ->
                val bit = (bits shr (sinal.bitLength - 1 - i)) and 1L
                if (bit == 1L) {
                    val idx = posicao / 8
                    require(idx < dlc) {
                        "${sinal.name} ocupa o byte $idx, alem do DLC $dlc do frame"
                    }
                    bytes[idx] = (bytes[idx].toInt() or (1 shl (posicao % 8))).toByte()
                }
            }
        }
        return bytes
    }
}
