package br.unb.baja.telemetry.adapter.web

import br.unb.baja.telemetry.adapter.web.dto.SessionDto
import br.unb.baja.telemetry.adapter.web.dto.SignalSummaryDto
import br.unb.baja.telemetry.adapter.web.error.SessionNotFoundException
import br.unb.baja.telemetry.application.port.SessionQuery
import br.unb.baja.telemetry.domain.can.dbc.DbcDatabase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
}
