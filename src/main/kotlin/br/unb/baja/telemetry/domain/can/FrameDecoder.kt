package br.unb.baja.telemetry.domain.can

import br.unb.baja.telemetry.domain.can.dbc.CanMessage

/**
 * Traduz os bytes de um frame em grandezas fisicas -- o coracao do sistema.
 *
 * A inversa do [FrameEncoder]. As duas usam a MESMA lista de posicoes de bit
 * ([SignalDefinition.bitPositionsMsbFirst]) -- o que significa que a propriedade
 * `encode(decode(bits)) == bits` NAO detecta um erro nessa lista: ele seria
 * simetrico, e a ida e volta fecharia num valor errado.
 *
 * Isso foi comprovado sabotando o codigo (docs/09 §3.4): um off-by-one no ramo
 * little endian passou pelo teste de propriedade e so foi pego pela comparacao
 * com os bytes do `cantools`, que e uma leitura independente do mesmo arquivo.
 * Por isso os dois tipos de teste existem.
 */
object FrameDecoder {

    /** Decodifica todos os sinais de uma mensagem. */
    fun decode(message: CanMessage, payload: ByteArray): List<SignalValue> =
        message.signals.map { decode(it, payload) }

    fun decode(signal: SignalDefinition, payload: ByteArray): SignalValue {
        val posicoes = signal.bitPositionsMsbFirst()

        // Um sinal declarado alem do payload recebido nao pode virar zero: zero
        // e um valor plausivel, e passaria despercebido no grafico.
        val ultimoByte = posicoes.maxOf { it / 8 }
        if (ultimoByte >= payload.size) {
            return SignalValue(
                name = signal.name,
                value = Double.NaN,
                valid = false,
                unit = signal.unit,
                invalidReason = "payload tem ${payload.size} bytes, mas o sinal ocupa ate o " +
                    "byte $ultimoByte",
            )
        }

        val bruto = lerBits(payload, posicoes)
        val comSinal = if (signal.signed) complementoDeDois(bruto, signal.bitLength) else bruto
        val fisico = signal.physicalOf(comSinal)

        return if (signal.isInRange(fisico)) {
            SignalValue(signal.name, fisico, valid = true, unit = signal.unit)
        } else {
            SignalValue(
                name = signal.name,
                value = fisico,
                valid = false,
                unit = signal.unit,
                invalidReason = "fora da faixa [${signal.min}, ${signal.max}] do DBC",
            )
        }
    }

    /** Devolve o padrao de bits cru, sem escala, offset nem complemento de dois. */
    fun rawBits(signal: SignalDefinition, payload: ByteArray): Long =
        lerBits(payload, signal.bitPositionsMsbFirst())

    /** Monta o valor lendo bit a bit, do mais significativo do sinal para o menos. */
    private fun lerBits(payload: ByteArray, posicoesMsbPrimeiro: List<Int>): Long {
        var valor = 0L
        for (posicao in posicoesMsbPrimeiro) {
            val bit = (payload[posicao / 8].toInt() shr (posicao % 8)) and 1
            valor = (valor shl 1) or bit.toLong()
        }
        return valor
    }

    /**
     * Complemento de dois com extensao de sinal a partir da largura DECLARADA,
     * nao de 8/16/32. Um sinal de 12 bits com o bit 11 ligado e negativo, mesmo
     * cabendo folgado num Int.
     */
    private fun complementoDeDois(bruto: Long, largura: Int): Long {
        val bitDeSinal = 1L shl (largura - 1)
        return if (bruto and bitDeSinal != 0L) bruto - (1L shl largura) else bruto
    }
}
