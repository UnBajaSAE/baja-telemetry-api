package br.unb.baja.telemetry.adapter.web

import br.unb.baja.telemetry.adapter.web.dto.SessionDto
import br.unb.baja.telemetry.application.port.SessionQuery
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class SessionController(private val sessions: SessionQuery) {

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
}
