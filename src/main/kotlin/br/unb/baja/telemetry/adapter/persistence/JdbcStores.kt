package br.unb.baja.telemetry.adapter.persistence

import br.unb.baja.telemetry.application.port.IngestBatchStore
import br.unb.baja.telemetry.application.port.RawFrameStore
import br.unb.baja.telemetry.application.port.SessionStore
import br.unb.baja.telemetry.application.port.SignalPointRow
import br.unb.baja.telemetry.application.port.SignalPointStore
import br.unb.baja.telemetry.domain.can.CanFrame
import br.unb.baja.telemetry.domain.session.SessionId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Conta quantas linhas um batchUpdate gravou.
 *
 * Com `reWriteBatchedInserts=true` o driver funde os INSERT num comando so e
 * PERDE a contagem por linha: devolve SUCCESS_NO_INFO (-2) para cada uma.
 * Somar direto daria um numero negativo -- e foi exatamente o que aconteceu
 * antes desta funcao existir (framesStored: -4 para 2 frames).
 *
 * O comando teria lancado excecao se falhasse, entao SUCCESS_NO_INFO significa
 * "gravou, mas nao sei dizer quantas": conta como 1.
 */
private fun IntArray.linhasGravadas(): Int =
    sumOf { if (it == Statement.SUCCESS_NO_INFO) 1 else maxOf(it, 0) }

@Repository
class JdbcSessionStore(private val jdbc: JdbcTemplate) : SessionStore {
    override fun upsert(id: SessionId) {
        // Mais seguro que "consultar se existe, e criar se nao existir": entre a
        // consulta e a criacao, outro lote poderia ter criado a mesma sessao.
        jdbc.update("INSERT INTO session (id) VALUES (?) ON CONFLICT DO NOTHING", id.value)
    }
}

@Repository
class JdbcIngestBatchStore(private val jdbc: JdbcTemplate) : IngestBatchStore {

    override fun registrar(
        batchId: UUID,
        sessionId: SessionId,
        deviceId: String,
        frameCount: Int,
        rejectedCount: Int,
        primeiroFrameEm: Instant?,
        ultimoFrameEm: Instant?,
    ): Boolean {
        // ON CONFLICT DO NOTHING devolve 0 linhas afetadas quando a chave ja
        // existe. E assim que o lote repetido e reconhecido -- pelo banco.
        //
        // O min/max vai nesta MESMA linha, que ja estava sendo inserida: e o que
        // permite o GET /sessions agregar sobre milhares de linhas em vez de
        // milhoes, sem nenhum lock disputado (ADR-011).
        val linhas = jdbc.update(
            """
            INSERT INTO ingest_batch
              (id, session_id, device_id, frame_count, rejected_count, first_frame_at, last_frame_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """,
            batchId, sessionId.value, deviceId, frameCount, rejectedCount,
            primeiroFrameEm?.let { Timestamp.from(it) },
            ultimoFrameEm?.let { Timestamp.from(it) },
        )
        return linhas > 0
    }

    override fun marcarDecodificado(batchId: UUID) {
        jdbc.update("UPDATE ingest_batch SET decoded_at = now() WHERE id = ?", batchId)
    }
}

@Repository
class JdbcRawFrameStore(private val jdbc: JdbcTemplate) : RawFrameStore {

    override fun gravarLote(
        sessionId: SessionId,
        deviceId: String,
        batchId: UUID,
        recebidoEm: Instant,
        frames: List<CanFrame>,
    ): Int {
        if (frames.isEmpty()) return 0
        // batchUpdate agrupa as linhas numa ida so ao banco. Com
        // reWriteBatchedInserts=true na URL, o driver ainda funde os INSERT num
        // unico comando -- ver a medicao no docs/06 §9.
        val afetadas = jdbc.batchUpdate(
            """
            INSERT INTO raw_frame
              (frame_time, session_id, device_id, can_id, payload, batch_id, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            frames.map {
                arrayOf(
                    Timestamp.from(it.readAt), sessionId.value, deviceId,
                    it.canId, it.payload, batchId, Timestamp.from(recebidoEm),
                )
            },
        )
        return afetadas.linhasGravadas()
    }
}

@Repository
class JdbcSignalPointStore(private val jdbc: JdbcTemplate) : SignalPointStore {

    override fun gravarLote(
        sessionId: SessionId,
        dbcVersion: String,
        pontos: List<SignalPointRow>,
    ): Int {
        if (pontos.isEmpty()) return 0
        val afetadas = jdbc.batchUpdate(
            """
            INSERT INTO signal_point
              (ts, session_id, signal_name, value, is_valid, can_id, dbc_version)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            pontos.map {
                arrayOf(
                    Timestamp.from(it.ts), sessionId.value, it.signalName,
                    it.value, it.valid, it.canId, dbcVersion,
                )
            },
        )
        return afetadas.linhasGravadas()
    }
}
