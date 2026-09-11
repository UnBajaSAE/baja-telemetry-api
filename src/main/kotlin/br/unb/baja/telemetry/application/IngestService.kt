package br.unb.baja.telemetry.application

import br.unb.baja.telemetry.application.port.IngestBatchStore
import br.unb.baja.telemetry.application.port.RawFrameStore
import br.unb.baja.telemetry.application.port.SessionStore
import br.unb.baja.telemetry.application.port.SignalPointStore
import br.unb.baja.telemetry.application.port.paraLinha
import br.unb.baja.telemetry.domain.can.CanFrame
import br.unb.baja.telemetry.domain.can.FrameDecoder
import br.unb.baja.telemetry.domain.can.FrameParse
import br.unb.baja.telemetry.domain.can.dbc.DbcDatabase
import br.unb.baja.telemetry.domain.session.SessionId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class IngestOutcome(
    val received: Int,
    val stored: Int,
    val signalsStored: Int,
    val rejections: List<FrameRejection>,
    val duplicate: Boolean,
)

data class FrameRejection(val index: Int, val reason: String, val detail: String)

/** Um frame como veio da rede: ainda sem confianca nenhuma. */
data class IncomingFrame(val epochMillis: Long, val canId: Int, val payloadHex: String)

@Service
class IngestService(
    private val sessions: SessionStore,
    private val batches: IngestBatchStore,
    private val rawFrames: RawFrameStore,
    private val signalPoints: SignalPointStore,
    private val dbc: DbcDatabase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * O fluxo do docs/07 §4, inteiro dentro de uma transacao.
     *
     * A ORDEM e a decisao de seguranca: o cru e gravado ANTES de qualquer
     * tentativa de decodificacao. Se o decodificador falhar, `decoded_at` fica
     * nulo e o reprocessamento sabe exatamente o que refazer -- sem perder nada
     * (ADR-001). O contrario custaria a telemetria do dia.
     */
    @Transactional
    fun ingest(
        batchId: UUID,
        sessionId: SessionId,
        deviceId: String,
        frames: List<IncomingFrame>,
    ): IngestOutcome {
        // 1. Valida cada frame. Um frame torto NAO derruba o lote (ADR-010).
        val aceitos = mutableListOf<CanFrame>()
        val rejeicoes = mutableListOf<FrameRejection>()
        frames.forEachIndexed { i, raw ->
            when (val p = CanFrame.parse(raw.epochMillis, raw.canId, raw.payloadHex)) {
                is FrameParse.Accepted -> aceitos += p.frame
                is FrameParse.Rejected -> rejeicoes += FrameRejection(i, p.reason.slug, p.detail)
            }
        }

        sessions.upsert(sessionId)

        // 2. Registrar o lote e o ponto de deteccao de repetido. A chave primaria
        //    decide -- nao uma consulta previa, que duas requisicoes simultaneas
        //    driblariam (ADR-008).
        val novo = batches.registrar(batchId, sessionId, deviceId, frames.size, rejeicoes.size)
        if (!novo) {
            log.info("Lote {} ja processado; nada foi gravado de novo", batchId)
            return IngestOutcome(frames.size, 0, 0, rejeicoes, duplicate = true)
        }

        // 3. O CRU PRIMEIRO. A partir daqui o dado esta a salvo.
        val recebidoEm = Instant.now()
        val gravados = rawFrames.gravarLote(sessionId, deviceId, batchId, recebidoEm, aceitos)

        // 4. So entao decodificar. Frame de canId ausente do DBC nao e erro:
        //    o cru fica guardado e um DBC futuro pode decodificar.
        val pontos = aceitos.flatMap { frame ->
            val msg = dbc.message(frame.canId) ?: return@flatMap emptyList()
            FrameDecoder.decode(msg, frame.payload)
                .map { it.paraLinha(frame.readAt, frame.canId) }
        }
        val sinais = signalPoints.gravarLote(sessionId, dbc.version, pontos)

        batches.marcarDecodificado(batchId)

        return IngestOutcome(frames.size, gravados, sinais, rejeicoes, duplicate = false)
    }
}
