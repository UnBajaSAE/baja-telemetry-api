package br.unb.baja.telemetry.application.port

import java.time.Instant

/** Uma sessao, do jeito que a listagem precisa dela. */
data class SessionSummary(
    val id: String,
    val description: String?,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val frameCount: Long,
    val rejectedCount: Long,
    val batchCount: Int,
) {
    /** Nula enquanto a sessao nao tiver nenhum lote com frame valido. */
    val durationSeconds: Long? =
        if (startedAt != null && endedAt != null) endedAt.epochSecond - startedAt.epochSecond else null
}

interface SessionQuery {
    fun listar(): List<SessionSummary>
}
