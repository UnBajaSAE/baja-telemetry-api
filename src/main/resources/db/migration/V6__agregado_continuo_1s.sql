-- O agregado continuo de 1 segundo.
--
-- POR QUE ELE EXISTE, com numero medido: o resumo de uma prova de enduro
-- (18 M pontos) custa 3.658 ms lendo signal_point direto, contra meta de 200 ms
-- no docs/10. Sobre este agregado, 29,7 ms -- 123x mais rapido.
--
-- A ideia e a do quadro branco do box: a prova ja acabou, aquelas medias nunca
-- mais mudam. Calcula-se UMA vez e le-se o resultado pronto.

CREATE MATERIALIZED VIEW signal_1s
WITH (
    timescaledb.continuous,
    -- AGREGACAO EM TEMPO REAL, ligada explicitamente.
    --
    -- Nas versoes recentes do TimescaleDB o padrao virou `true`, ou seja, a
    -- consulta so enxerga o que a politica ja materializou. Com isso, quem
    -- termina uma bateria e pede o resumo na hora nao ve nada por ate um minuto
    -- -- o classico "mandei e nao apareceu", que e justamente o problema que a
    -- decodificacao sincrona do docs/07 §5 quis evitar.
    --
    -- Com `false`, o banco une a parte materializada a uma leitura ao vivo do
    -- que veio depois. Custa varrer o rabo recente; a troca vale porque esse
    -- rabo e de minutos, nao de horas.
    timescaledb.materialized_only = false
) AS
SELECT session_id,
       signal_name,
       can_id,
       time_bucket('1 second', ts) AS bucket,
       min(value) AS min_value,
       max(value) AS max_value,
       -- SOMA e CONTAGEM, nao media. Guardar avg() aqui obrigaria quem consulta
       -- a tirar media das medias, o que so da certo se todas as janelas tiverem
       -- o mesmo numero de pontos. Com 2 leituras a 800 rpm numa janela e 198 a
       -- 4000 na seguinte, a media das medias da 2.400 rpm; a ponderada da 3.968.
       -- Na nossa telemetria de taxa constante as janelas sao quase uniformes e o
       -- erro seria pequeno -- mas gaps e bordas de sessao existem, e guardar as
       -- duas colunas custa nada.
       sum(value) AS sum_value,
       count(*)   AS n,
       count(*) FILTER (WHERE NOT is_valid) AS n_invalid
FROM signal_point
GROUP BY session_id, signal_name, can_id, bucket;

-- COMMENT ON VIEW, e nao ON MATERIALIZED VIEW: o TimescaleDB cria o agregado
-- continuo com sintaxe de materialized view, mas o objeto que fica no catalogo
-- e uma VIEW comum sobre uma hypertable interna.
COMMENT ON VIEW signal_1s IS
  'Agregado continuo de 1s por sinal. Some sum_value/n para media PONDERADA; '
  'nunca tire media das medias. Ver ADR-012 e docs/06.';

-- A janela de 8 dias NAO e exagero: o docs/03 aceita frames com ate 7 dias de
-- idade, e o ADR-004 diz que dado chega FORA DE ORDEM -- um buffer de ontem
-- pode aparecer hoje, com carimbo de ontem. Uma janela de 1 dia deixaria esse
-- dado sem materializar para sempre.
--
-- Nao custa varrer 8 dias a cada minuto: o TimescaleDB registra quais janelas
-- foram invalidadas por insercoes e so reprocessa essas.
--
-- O end_offset de 1 minuto evita ficar recalculando a janela que ainda esta
-- recebendo dado.
SELECT add_continuous_aggregate_policy('signal_1s',
    start_offset      => INTERVAL '8 days',
    end_offset        => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute');
