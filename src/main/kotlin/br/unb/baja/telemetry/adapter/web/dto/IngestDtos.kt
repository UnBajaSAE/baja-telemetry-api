package br.unb.baja.telemetry.adapter.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * O corpo do POST /api/v1/ingest, exatamente como o docs/03 define.
 *
 * Classe separada do dominio de proposito: o JSON e contrato EXTERNO e muda por
 * razoes externas (versao da API, conveniencia do firmware). Se o Jackson
 * desserializasse direto para as classes de dominio, toda mudanca de formato
 * viraria mudanca no decodificador.
 *
 * Os campos sao nao-nulos: campo ausente faz o Jackson falhar na leitura, o que
 * vira 400 no ProblemDetailsHandler.
 */
data class IngestRequestDto(
    val batchId: UUID,

    @field:NotBlank
    @field:Size(max = 64)
    val deviceId: String,

    @field:NotBlank
    val sessionId: String,

    // O limite de 5.000 NAO esta aqui: passar dele e 413, nao 400 (docs/08),
    // e Bean Validation so sabe produzir 400. A checagem fica no controller.
    @field:NotEmpty
    val frames: List<RawFrameDto>,
)

data class RawFrameDto(
    /** Epoch em ms do relogio DO DISPOSITIVO, nao do envio (ADR-004). */
    val t: Long,
    /** Identificador CAN em decimal: 0x100 chega como 256. */
    val id: Int,
    /** Payload em hex. Validado no dominio, nao aqui -- ver ADR-010. */
    val data: String,
)

data class IngestResponseDto(
    val batchId: UUID,
    val framesReceived: Int,
    val framesStored: Int,
    val framesRejected: Int,
    val duplicate: Boolean,
    val rejections: List<RejectionDto>,
)

data class RejectionDto(val index: Int, val reason: String, val detail: String)
