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

/** Resumo de um sinal ao longo de uma sessao inteira. */
data class SignalSummary(
    val signal: String,
    val canId: Int,
    val min: Double,
    val max: Double,
    /** Media PONDERADA: soma das somas dividida pela soma das contagens. */
    val avg: Double,
    val count: Long,
    /** Pontos fora da faixa do DBC. Gravados e marcados, nao descartados. */
    val invalidCount: Long,
)

/** Um ponto da serie temporal agregada. */
data class MetricPoint(
    val bucket: Instant,
    /** Nulos quando a janela so teve leituras invalidas. */
    val min: Double?,
    val max: Double?,
    val avg: Double?,
    val count: Long,
    val invalidCount: Long,
)

interface SessionQuery {
    fun listar(): List<SessionSummary>

    /** Null se a sessao nao existe -- diferente de existir e estar vazia. */
    fun resumo(sessionId: String): List<SignalSummary>?

    /** A janela de tempo coberta por uma sessao, ou null se ela nao existe. */
    fun intervalo(sessionId: String): Pair<Instant, Instant>?

    fun metricas(
        sessionId: String,
        signal: String,
        de: Instant,
        ate: Instant,
        janelaSegundos: Long,
    ): List<MetricPoint>
}
