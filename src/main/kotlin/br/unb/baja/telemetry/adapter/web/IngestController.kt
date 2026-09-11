package br.unb.baja.telemetry.adapter.web

import br.unb.baja.telemetry.adapter.web.dto.IngestRequestDto
import br.unb.baja.telemetry.adapter.web.dto.IngestResponseDto
import br.unb.baja.telemetry.adapter.web.dto.RejectionDto
import br.unb.baja.telemetry.adapter.web.error.BatchTooLargeException
import br.unb.baja.telemetry.adapter.web.error.InvalidSessionIdException
import br.unb.baja.telemetry.application.IncomingFrame
import br.unb.baja.telemetry.application.IngestService
import br.unb.baja.telemetry.domain.session.SessionId
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class IngestController(
    private val ingestService: IngestService,
) {

    /**
     * Recebe um lote de frames CAN crus (docs/03).
     *
     * O controller so confere a FORMA. Ele nao pergunta se o canId existe no DBC
     * -- isso e pergunta de dominio, e o controller fala HTTP, nao dominio.
     */
    @PostMapping("/ingest")
    fun ingest(@Valid @RequestBody req: IngestRequestDto): IngestResponseDto {
        if (req.frames.size > MAX_FRAMES_PER_BATCH) {
            throw BatchTooLargeException(req.frames.size, MAX_FRAMES_PER_BATCH)
        }

        val sessionId = SessionId.parseOrNull(req.sessionId)
            ?: throw InvalidSessionIdException(req.sessionId)

        val outcome = ingestService.ingest(
            batchId = req.batchId,
            sessionId = sessionId,
            deviceId = req.deviceId,
            frames = req.frames.map { IncomingFrame(it.t, it.id, it.data) },
        )

        return IngestResponseDto(
            batchId = req.batchId,
            framesReceived = outcome.received,
            framesStored = outcome.stored,
            framesRejected = outcome.rejections.size,
            duplicate = outcome.duplicate,
            rejections = outcome.rejections.map { RejectionDto(it.index, it.reason, it.detail) },
        )
    }

    private companion object {
        /** docs/03: no maximo 5.000 frames por lote. */
        const val MAX_FRAMES_PER_BATCH = 5_000
    }
}
