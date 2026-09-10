package br.unb.baja.telemetry.application

import br.unb.baja.telemetry.domain.can.CanFrame
import br.unb.baja.telemetry.domain.can.FrameParse
import br.unb.baja.telemetry.domain.session.SessionId
import org.springframework.stereotype.Service

/** O que a API decidiu sobre um lote. */
data class IngestOutcome(
    val received: Int,
    val accepted: List<CanFrame>,
    val rejections: List<FrameRejection>,
    /** Quantos frames foram de fato gravados. Zero ate a persistencia da Fase 2. */
    val stored: Int,
    val duplicate: Boolean,
)

data class FrameRejection(val index: Int, val reason: String, val detail: String)

@Service
class IngestService {

    /**
     * Valida os frames de um lote, um a um.
     *
     * Frame invalido NAO derruba o lote (ADR-010): ele entra na lista de
     * rejeicoes e o resto segue. Um bit invertido no cartao SD nao pode custar
     * os outros 999 frames.
     *
     * ATENCAO -- checkpoint 1.3: nada e persistido ainda, entao `stored` e sempre
     * zero. A gravacao entra na Fase 2. Nao apontar firmware real para este
     * endpoint enquanto isso: o ESP32 apaga o buffer ao ver 2xx (docs/08).
     */
    fun ingest(sessionId: SessionId, deviceId: String, frames: List<IncomingFrame>): IngestOutcome {
        val accepted = mutableListOf<CanFrame>()
        val rejections = mutableListOf<FrameRejection>()

        frames.forEachIndexed { index, raw ->
            when (val parsed = CanFrame.parse(raw.epochMillis, raw.canId, raw.payloadHex)) {
                is FrameParse.Accepted -> accepted += parsed.frame
                is FrameParse.Rejected ->
                    rejections += FrameRejection(index, parsed.reason.slug, parsed.detail)
            }
        }

        return IngestOutcome(
            received = frames.size,
            accepted = accepted,
            rejections = rejections,
            stored = 0,
            duplicate = false,
        )
    }
}

/** Um frame como veio da rede: ainda sem confianca nenhuma. */
data class IncomingFrame(val epochMillis: Long, val canId: Int, val payloadHex: String)
