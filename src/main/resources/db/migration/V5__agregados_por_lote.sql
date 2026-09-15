-- Duracao e contagem de uma sessao, sem varrer o cru e sem lock disputado.
--
-- O GET /sessions precisa de min/max do horario dos frames e do total por
-- sessao. Havia tres caminhos, e dois foram descartados com medicao (ADR-011):
--
--   varrer raw_frame            220 ms com 2,2 M linhas -- estoura a meta de
--                               100 ms do docs/10 antes de uma prova de enduro
--   UPDATE na linha da sessao   trivial de consultar, mas todo lote passa a
--                               disputar a MESMA linha, e o checkpoint 2.6
--                               provou que isso serializa requisicoes
--
-- O caminho escolhido: cada lote grava o PROPRIO min/max, na linha que ja
-- estava sendo inserida. Zero contencao, e a consulta agrega sobre milhares de
-- linhas em vez de milhoes -- medido em 1,5 ms.

ALTER TABLE ingest_batch
    ADD COLUMN first_frame_at TIMESTAMPTZ,
    ADD COLUMN last_frame_at  TIMESTAMPTZ;

COMMENT ON COLUMN ingest_batch.first_frame_at IS 'Menor frame_time deste lote, pelo relogio do '
                                                 'DISPOSITIVO. Nulo se o lote nao trouxe nenhum '
                                                 'frame valido.';
COMMENT ON COLUMN ingest_batch.last_frame_at  IS 'Maior frame_time deste lote. Com o first_, '
                                                 'permite responder duracao da sessao sem tocar '
                                                 'em raw_frame (ADR-011).';

-- Preenche o que ja existia. Aceitavel porque roda uma vez; num banco grande
-- vale rodar fora do horario de coleta.
UPDATE ingest_batch b
   SET first_frame_at = f.primeiro,
       last_frame_at  = f.ultimo
  FROM (
    SELECT batch_id, min(frame_time) AS primeiro, max(frame_time) AS ultimo
      FROM raw_frame GROUP BY batch_id
  ) f
 WHERE f.batch_id = b.id;

CREATE INDEX idx_batch_sessao_inicio ON ingest_batch (session_id, first_frame_at);

-- session.started_at e session.ended_at nunca foram preenchidas: o docs/06 as
-- declarou como derivadas e nada as escrevia. Uma coluna sempre nula com esse
-- nome e armadilha para a proxima pessoa -- a informacao agora vem da
-- ingest_batch. Removidas em vez de deixadas mentindo.
ALTER TABLE session
    DROP COLUMN started_at,
    DROP COLUMN ended_at;
