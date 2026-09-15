package br.unb.baja.telemetry.application.port

import br.unb.baja.telemetry.domain.can.CanFrame
import br.unb.baja.telemetry.domain.can.SignalValue
import br.unb.baja.telemetry.domain.session.SessionId
import java.time.Instant
import java.util.UUID

/**
 * O que a aplicacao precisa do armazenamento.
 *
 * As interfaces sao declaradas AQUI, onde sao usadas, e implementadas no
 * adaptador. Uma indirecao so, e ela existe por um motivo concreto: permitir
 * testar o IngestService sem banco (ADR-009).
 */

interface SessionStore {
    /** Cria a sessao se ainda nao existir. Idempotente por natureza (ADR-007). */
    fun upsert(id: SessionId)
}

interface IngestBatchStore {
    /**
     * Registra o lote.
     *
     * @return false se este batchId JA foi processado. A deteccao e da chave
     *   primaria, nao de uma consulta previa: duas requisicoes simultaneas
     *   driblariam um `if (jaExiste)`, mas nao driblam o banco (ADR-008).
     */
    fun registrar(
        batchId: UUID,
        sessionId: SessionId,
        deviceId: String,
        frameCount: Int,
        rejectedCount: Int,
        /** Menor e maior `frame_time` do lote. Nulos se nenhum frame foi aceito. */
        primeiroFrameEm: Instant?,
        ultimoFrameEm: Instant?,
    ): Boolean

    fun marcarDecodificado(batchId: UUID)
}

interface RawFrameStore {
    fun gravarLote(sessionId: SessionId, deviceId: String, batchId: UUID, recebidoEm: Instant, frames: List<CanFrame>): Int
}

/** Um ponto de sinal pronto para gravar. */
data class SignalPointRow(
    val ts: Instant,
    val signalName: String,
    val value: Double,
    val valid: Boolean,
    val canId: Int,
)

interface SignalPointStore {
    fun gravarLote(sessionId: SessionId, dbcVersion: String, pontos: List<SignalPointRow>): Int
}

/** Converte o resultado do decodificador em linha de banco. */
fun SignalValue.paraLinha(ts: Instant, canId: Int) =
    SignalPointRow(ts, name, if (value.isNaN()) 0.0 else value, valid, canId)
