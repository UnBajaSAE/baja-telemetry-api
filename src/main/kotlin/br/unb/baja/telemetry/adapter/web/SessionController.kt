package br.unb.baja.telemetry.adapter.web

import br.unb.baja.telemetry.adapter.web.dto.SessionDto
import br.unb.baja.telemetry.adapter.web.dto.SignalSummaryDto
import br.unb.baja.telemetry.adapter.web.dto.MetricPointDto
import br.unb.baja.telemetry.adapter.web.dto.MetricsDto
import br.unb.baja.telemetry.adapter.web.error.InvalidBucketException
import br.unb.baja.telemetry.adapter.web.error.SessionNotFoundException
import br.unb.baja.telemetry.adapter.web.error.TooManyPointsException
import br.unb.baja.telemetry.adapter.web.error.UnknownSignalException
import br.unb.baja.telemetry.domain.Bucket
import br.unb.baja.telemetry.application.port.SessionQuery
import br.unb.baja.telemetry.domain.can.dbc.DbcDatabase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class SessionController(
    private val sessions: SessionQuery,
    private val dbc: DbcDatabase,
) {

    /**
     * Lista as sessoes, da mais recente para a mais antiga.
     *
     * "Mais recente" e pelo horario dos FRAMES, nao pelo de criacao da linha:
     * um lote bufferizado por horas chega depois, mas pertence ao momento em
     * que o carro estava na pista (ADR-004).
     */
    @GetMapping("/sessions")
    fun listar(): List<SessionDto> = sessions.listar().map {
        SessionDto(
            id = it.id,
            description = it.description,
            startedAt = it.startedAt?.toString(),
            endedAt = it.endedAt?.toString(),
            durationSeconds = it.durationSeconds,
            frameCount = it.frameCount,
            rejectedCount = it.rejectedCount,
            batchCount = it.batchCount,
        )
    }

    /**
     * Resumo por sinal da sessao inteira: minimo, maximo, media e quantos pontos
     * ficaram marcados como invalidos.
     *
     * E a consulta que responde "qual foi a temperatura maxima na prova?".
     */
    @GetMapping("/sessions/{id}/summary")
    fun resumo(@PathVariable id: String): List<SignalSummaryDto> {
        val resumo = sessions.resumo(id) ?: throw SessionNotFoundException(id)
        return resumo.map {
            SignalSummaryDto(
                signal = it.signal,
                // A unidade vem do DBC, nao do banco: a fonte de verdade e o
                // arquivo (ADR-006). Nula se o sinal sumiu do mapa desde entao.
                unit = dbc.signal(it.canId, it.signal)?.unit,
                canId = it.canId,
                min = it.min,
                max = it.max,
                avg = it.avg,
                count = it.count,
                invalidCount = it.invalidCount,
            )
        }
    }

    /**
     * Serie temporal de UM sinal, agregada por janela.
     *
     * `from`/`to` sao opcionais: sem eles, cobre a sessao inteira.
     */
    @GetMapping("/sessions/{id}/metrics")
    fun metricas(
        @PathVariable id: String,
        @RequestParam signal: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false, defaultValue = "1s") bucket: String,
    ): MetricsDto {
        val janela = Bucket.parseOrNull(bucket) ?: throw InvalidBucketException(bucket)

        val conhecidos = dbc.messages.flatMap { m -> m.signals.map { it.name } }.sorted()
        // Sinal com erro de digitacao devolveria lista vazia em silencio -- o
        // modo de falha que este projeto mais evita. Melhor recusar e dizer
        // quais existem.
        if (signal !in conhecidos) throw UnknownSignalException(signal, conhecidos)

        val (inicioSessao, fimSessao) = sessions.intervalo(id)
            ?: throw SessionNotFoundException(id)

        val de = from?.let(Instant::parse) ?: inicioSessao
        val ate = to?.let(Instant::parse) ?: fimSessao.plusSeconds(1)

        val estimado = (ate.epochSecond - de.epochSecond) / janela.seconds
        if (estimado > MAX_PONTOS) {
            val sugerido = Bucket.parseOrNull(
                "${((ate.epochSecond - de.epochSecond) / MAX_PONTOS + 1)}s",
            )
            throw TooManyPointsException(estimado, MAX_PONTOS, sugerido?.toString() ?: "1h")
        }

        val pontos = sessions.metricas(id, signal, de, ate, janela.seconds)
        return MetricsDto(
            sessionId = id,
            signal = signal,
            unit = dbc.messages.firstNotNullOfOrNull { it.signal(signal) }?.unit,
            bucket = janela.toString(),
            from = de.toString(),
            to = ate.toString(),
            points = pontos.map {
                MetricPointDto(it.bucket.toString(), it.min, it.max, it.avg, it.count, it.invalidCount)
            },
        )
    }

    private companion object {
        /** Acima disto a resposta vira megabytes de JSON sem serventia num grafico. */
        const val MAX_PONTOS = 20_000
    }
}
