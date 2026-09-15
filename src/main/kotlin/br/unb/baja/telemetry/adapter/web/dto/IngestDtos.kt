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

/** Uma sessao na listagem do `GET /api/v1/sessions`. */
data class SessionDto(
    val id: String,
    val description: String?,
    /** ISO-8601. Nulo enquanto a sessao nao tiver frame valido nenhum. */
    val startedAt: String?,
    val endedAt: String?,
    val durationSeconds: Long?,
    val frameCount: Long,
    val rejectedCount: Long,
    val batchCount: Int,
)

/** Um sinal no resumo de `GET /api/v1/sessions/{id}/summary`. */
data class SignalSummaryDto(
    val signal: String,
    /** Unidade lida do DBC, nao do banco -- a fonte de verdade e o arquivo. */
    val unit: String?,
    val canId: Int,
    val min: Double,
    val max: Double,
    val avg: Double,
    val count: Long,
    val invalidCount: Long,
)

/** Resposta de `GET /api/v1/sessions/{id}/metrics`. */
data class MetricsDto(
    val sessionId: String,
    val signal: String,
    val unit: String?,
    /** A janela efetivamente usada, normalizada (ex.: "60s" vira "1m"). */
    val bucket: String,
    val from: String,
    val to: String,
    val points: List<MetricPointDto>,
)

data class MetricPointDto(
    val bucket: String,
    /** Nulos quando a janela só teve leituras inválidas. */
    val min: Double?,
    val max: Double?,
    val avg: Double?,
    val count: Long,
    val invalidCount: Long,
)
