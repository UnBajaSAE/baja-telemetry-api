package br.unb.baja.telemetry.adapter.persistence

import br.unb.baja.telemetry.application.port.SessionQuery
import br.unb.baja.telemetry.application.port.SessionSummary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class JdbcSessionQuery(private val jdbc: JdbcTemplate) : SessionQuery {

    /**
     * Agrega sobre `ingest_batch`, nao sobre `raw_frame` (ADR-011).
     *
     * Medido com 2,2 M frames: 1,5 ms aqui contra 220 ms varrendo o cru -- e a
     * diferenca cresce, porque raw_frame acompanha a temporada e ingest_batch
     * acompanha o numero de lotes.
     *
     * LEFT JOIN de proposito: uma sessao recem-criada, sem nenhum lote ainda,
     * precisa aparecer na lista com contagem zero em vez de sumir.
     */
    override fun listar(): List<SessionSummary> = jdbc.query(
        """
        SELECT s.id,
               s.description,
               min(b.first_frame_at)             AS started_at,
               max(b.last_frame_at)              AS ended_at,
               coalesce(sum(b.frame_count), 0)   AS frame_count,
               coalesce(sum(b.rejected_count),0) AS rejected_count,
               count(b.id)                       AS batch_count
          FROM session s
          LEFT JOIN ingest_batch b ON b.session_id = s.id
         GROUP BY s.id, s.description, s.created_at
         ORDER BY min(b.first_frame_at) DESC NULLS LAST, s.created_at DESC
        """,
        ::mapear,
    )

    private fun mapear(rs: ResultSet, @Suppress("UNUSED_PARAMETER") linha: Int) = SessionSummary(
        id = rs.getString("id"),
        description = rs.getString("description"),
        startedAt = rs.getTimestamp("started_at")?.toInstant(),
        endedAt = rs.getTimestamp("ended_at")?.toInstant(),
        frameCount = rs.getLong("frame_count"),
        rejectedCount = rs.getLong("rejected_count"),
        batchCount = rs.getInt("batch_count"),
    )
}
