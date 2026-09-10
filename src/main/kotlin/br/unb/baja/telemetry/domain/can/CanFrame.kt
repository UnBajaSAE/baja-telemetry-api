package br.unb.baja.telemetry.domain.can

import java.time.Instant

/**
 * Um frame CAN cru, ja validado.
 *
 * O payload continua opaco de proposito: interpretar os bytes exige o mapa de
 * sinais (DBC), e isso e trabalho do decodificador na Fase 2. Aqui so garantimos
 * que o que chegou tem forma de frame.
 */
class CanFrame private constructor(
    /** Instante da leitura NO DISPOSITIVO -- nao o do recebimento (ADR-004). */
    val readAt: Instant,
    val canId: Int,
    val payload: ByteArray,
) {

    /**
     * ByteArray usa igualdade por identidade em Kotlin: dois arrays com os mesmos
     * bytes sao considerados diferentes. Sem sobrescrever isso, dois frames iguais
     * nunca seriam iguais -- e o teste de ida e volta da Fase 2 falharia sem motivo
     * aparente.
     */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is CanFrame &&
                readAt == other.readAt &&
                canId == other.canId &&
                payload.contentEquals(other.payload))

    override fun hashCode(): Int =
        (readAt.hashCode() * 31 + canId) * 31 + payload.contentHashCode()

    override fun toString(): String =
        "CanFrame(readAt=$readAt, canId=0x${canId.toString(16).uppercase()}, " +
            "payload=${payload.toHex()})"

    companion object {
        /** Um frame CAN carrega no maximo 8 bytes de dado (docs/01). */
        const val MAX_PAYLOAD_BYTES = 8

        /** CAN 2.0A: identificador de 11 bits. Estendido (29 bits) esta fora de escopo. */
        const val MAX_CAN_ID = 0x7FF

        private val HEX = Regex("^[0-9A-Fa-f]+$")

        /**
         * Tenta montar um frame a partir do que veio na rede.
         *
         * Devolve um resultado em vez de lancar excecao porque, pelo ADR-010, um
         * frame invalido NAO derruba o lote: ele e rejeitado individualmente e o
         * resto do lote entra. Excecao aqui forcaria try/catch no laco.
         */
        fun parse(epochMillis: Long, canId: Int, payloadHex: String): FrameParse {
            if (canId !in 0..MAX_CAN_ID) {
                return FrameParse.Rejected(
                    RejectionReason.CAN_ID_OUT_OF_RANGE,
                    "canId $canId fora da faixa 0..$MAX_CAN_ID (CAN 2.0A, 11 bits)",
                )
            }
            if (payloadHex.isEmpty()) {
                return FrameParse.Rejected(RejectionReason.MALFORMED_FRAME, "payload vazio")
            }
            if (payloadHex.length % 2 != 0) {
                return FrameParse.Rejected(
                    RejectionReason.MALFORMED_FRAME,
                    "payload tem numero impar de digitos hex: '$payloadHex'",
                )
            }
            if (!HEX.matches(payloadHex)) {
                return FrameParse.Rejected(
                    RejectionReason.MALFORMED_FRAME,
                    "payload tem caractere nao-hexadecimal: '$payloadHex'",
                )
            }
            val bytes = payloadHex.length / 2
            if (bytes > MAX_PAYLOAD_BYTES) {
                return FrameParse.Rejected(
                    RejectionReason.MALFORMED_FRAME,
                    "payload tem $bytes bytes; um frame CAN carrega no maximo $MAX_PAYLOAD_BYTES",
                )
            }
            return FrameParse.Accepted(
                CanFrame(Instant.ofEpochMilli(epochMillis), canId, payloadHex.decodeHex()),
            )
        }
    }
}

private fun String.decodeHex(): ByteArray =
    ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

internal fun ByteArray.toHex(): String =
    joinToString("") { "%02X".format(it) }
