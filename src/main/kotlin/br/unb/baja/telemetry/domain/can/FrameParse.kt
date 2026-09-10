package br.unb.baja.telemetry.domain.can

/** Resultado de tentar montar um frame a partir do que chegou na rede. */
sealed interface FrameParse {
    data class Accepted(val frame: CanFrame) : FrameParse
    data class Rejected(val reason: RejectionReason, val detail: String) : FrameParse
}

/**
 * Motivos de rejeicao de um frame individual.
 *
 * O `slug` e o identificador ESTAVEL que vai para a resposta HTTP -- o firmware
 * decide por ele, nunca pelo texto do `detail` (docs/08). Renomear um slug quebra
 * o cliente.
 */
enum class RejectionReason(val slug: String) {
    MALFORMED_FRAME("malformed-frame"),
    CAN_ID_OUT_OF_RANGE("can-id-out-of-range"),
}
