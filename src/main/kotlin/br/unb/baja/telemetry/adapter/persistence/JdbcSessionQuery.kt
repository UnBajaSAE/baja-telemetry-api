package br.unb.baja.telemetry.adapter.persistence

import br.unb.baja.telemetry.application.port.SessionQuery
import br.unb.baja.telemetry.application.port.SessionSummary
import br.unb.baja.telemetry.application.port.MetricPoint
import br.unb.baja.telemetry.application.port.SignalSummary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

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

    /**
     * Resumo por sinal, lido do agregado continuo `signal_1s` (ADR-012).
     *
     * Medido numa prova de enduro de 18 M pontos: 29,7 ms aqui, contra 3.658 ms
     * agregando `signal_point` direto -- e a meta do docs/10 e 200 ms.
     *
     * A media e PONDERADA: `sum(sum_value) / sum(n)`. Tirar `avg()` das medias
     * de cada janela so daria certo se todas tivessem o mesmo numero de pontos.
     */
    override fun resumo(sessionId: String): List<SignalSummary>? {
        val existe = jdbc.queryForObject(
            "SELECT count(*) FROM session WHERE id = ?", Int::class.java, sessionId,
        )!! > 0
        if (!existe) return null

        return jdbc.query(
            """
            SELECT signal_name,
                   can_id,
                   min(min_value)                          AS minimo,
                   max(max_value)                          AS maximo,
                   -- Ponderada por n_valid, nao por n: os invalidos nao entram
                   -- na soma, entao nao podem entrar no divisor (V7).
                   sum(sum_value) / nullif(sum(n_valid), 0) AS media,
                   sum(n)                                   AS pontos,
                   sum(n) - sum(n_valid)                    AS invalidos
              FROM signal_1s
             WHERE session_id = ?
             GROUP BY signal_name, can_id
             ORDER BY signal_name
            """,
            { rs, _ ->
                SignalSummary(
                    signal = rs.getString("signal_name"),
                    canId = rs.getInt("can_id"),
                    min = rs.getDouble("minimo"),
                    max = rs.getDouble("maximo"),
                    avg = rs.getDouble("media"),
                    count = rs.getLong("pontos"),
                    invalidCount = rs.getLong("invalidos"),
                )
            },
            sessionId,
        )
    }

    override fun intervalo(sessionId: String): Pair<Instant, Instant>? = jdbc.query(
        """
        SELECT min(first_frame_at) AS de, max(last_frame_at) AS ate
          FROM ingest_batch WHERE session_id = ?
        """,
        { rs, _ ->
            val de = rs.getTimestamp("de")?.toInstant()
            val ate = rs.getTimestamp("ate")?.toInstant()
            if (de != null && ate != null) de to ate else null
        },
        sessionId,
    ).firstOrNull()

    /**
     * Serie temporal agregada por janela, lida do agregado continuo (ADR-012).
     *
     * A janela pedida e REAGRUPADA sobre as janelas de 1 s: `time_bucket` sobre
     * a coluna `bucket`, somando as somas e as contagens. Por isso ela precisa
     * ser multipla de 1 s -- reagrupar 1 s em janelas de 2,5 s desalinharia as
     * bordas, e o `Bucket` recusa isso antes de chegar aqui.
     */
    override fun metricas(
        sessionId: String,
        signal: String,
        de: Instant,
        ate: Instant,
        janelaSegundos: Long,
    ): List<MetricPoint> = jdbc.query(
        """
        SELECT time_bucket(make_interval(secs => ?), bucket)  AS janela,
               min(min_value)                                 AS minimo,
               max(max_value)                                 AS maximo,
               sum(sum_value) / nullif(sum(n_valid), 0)       AS media,
               sum(n)                                         AS pontos,
               sum(n) - sum(n_valid)                          AS invalidos
          FROM signal_1s
         WHERE session_id = ? AND signal_name = ?
           AND bucket >= ? AND bucket < ?
         GROUP BY janela
         ORDER BY janela
        """,
        { rs, _ ->
            MetricPoint(
                bucket = rs.getTimestamp("janela").toInstant(),
                min = rs.getObject("minimo")?.let { (it as Number).toDouble() },
                max = rs.getObject("maximo")?.let { (it as Number).toDouble() },
                avg = rs.getObject("media")?.let { (it as Number).toDouble() },
                count = rs.getLong("pontos"),
                invalidCount = rs.getLong("invalidos"),
            )
        },
        janelaSegundos.toDouble(), sessionId, signal,
        Timestamp.from(de), Timestamp.from(ate),
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
