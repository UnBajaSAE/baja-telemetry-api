-- Refaz o agregado de 1 s para que as estatisticas cubram SO as leituras dentro
-- da faixa do DBC.
--
-- O problema que isto corrige: um sensor desconectado manda 0xFF em tudo, que no
-- RPM vira 16.383,75. Com esse ponto dentro do min/max/media, o resumo passa a
-- dizer "RPM maximo 16.383" para um motor que nao passa de 8.000 -- e num
-- grafico, um unico espeto desses arruina a escala inteira.
--
-- Isso nao e informacao extra, e informacao ERRADA. O ponto continua gravado e
-- contado (ADR-010 e o docs/06 §3.4 seguem valendo: saber que o sensor caiu as
-- 09:52 tambem e informacao) -- ele so deixa de contaminar a estatistica.
--
-- Descoberto ao desenhar o endpoint de metricas do checkpoint 3.3.

DROP MATERIALIZED VIEW signal_1s CASCADE;

CREATE MATERIALIZED VIEW signal_1s
WITH (
    timescaledb.continuous,
    -- Ver ADR-012: o padrao virou `true` nas versoes recentes, e com ele dado
    -- recem-gravado ficaria invisivel ate a politica rodar.
    timescaledb.materialized_only = false
) AS
SELECT session_id,
       signal_name,
       can_id,
       time_bucket('1 second', ts) AS bucket,
       -- Estatisticas SO do que faz sentido fisico.
       min(value) FILTER (WHERE is_valid) AS min_value,
       max(value) FILTER (WHERE is_valid) AS max_value,
       -- SOMA e CONTAGEM, nunca avg(): quem consulta precisa poder ponderar.
       -- Ver ADR-012 e o caso das janelas desiguais.
       sum(value) FILTER (WHERE is_valid) AS sum_value,
       count(*)   FILTER (WHERE is_valid) AS n_valid,
       -- Total, incluindo os invalidos. `invalidos = n - n_valid`.
       count(*) AS n
FROM signal_point
GROUP BY session_id, signal_name, can_id, bucket;

COMMENT ON VIEW signal_1s IS
  'Agregado continuo de 1s por sinal. min/max/sum cobrem SO leituras validas; '
  'n e o total e n_valid o que entrou na estatistica, entao invalidos = n - n_valid. '
  'Media PONDERADA = sum_value / n_valid. Ver ADR-012.';

SELECT add_continuous_aggregate_policy('signal_1s',
    start_offset      => INTERVAL '8 days',
    end_offset        => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute');
