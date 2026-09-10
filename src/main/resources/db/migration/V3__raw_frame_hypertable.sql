-- raw_frame: o frame cru, imutavel, append-only. E a FONTE DA VERDADE (ADR-001).
--
-- Descobrir em novembro que a escala de um sensor estava errada desde agosto
-- deixa de ser perda de temporada porque esta tabela permite reprocessar. Por
-- isso ela nunca e apagada -- so comprimida.
--
-- Justificativas completas: docs/06-modelo-de-dados.md

CREATE TABLE raw_frame (
    -- Relogio do DISPOSITIVO: quando o frame foi LIDO no barramento.
    frame_time   TIMESTAMPTZ NOT NULL,
    session_id   TEXT        NOT NULL,
    device_id    TEXT        NOT NULL,
    can_id       INTEGER     NOT NULL,
    payload      BYTEA       NOT NULL,
    batch_id     UUID        NOT NULL,
    -- Relogio do SERVIDOR: quando o lote chegou. Podem separar minutos.
    received_at  TIMESTAMPTZ NOT NULL
);

-- SURPRESA 1 -- sem chave primaria, de proposito.
-- Todo indice unico numa hypertable precisa incluir a coluna de particionamento:
-- por baixo os chunks sao tabelas separadas, e o Postgres nao garante unicidade
-- entre elas. `id BIGSERIAL PRIMARY KEY` seria recusado com
--   "cannot create a unique index without the column ts (used in partitioning)"
-- A tabela e append-only e a unicidade e garantida no nivel do lote, pela chave
-- primaria de ingest_batch (ADR-008). PK aqui seria custo de indice sem funcao.

-- SURPRESA 2 -- sem REFERENCES em batch_id, de proposito.
-- Chave estrangeira numa hypertable paga uma verificacao POR LINHA inserida:
-- num lote de 5.000 frames, sao 5.000 verificacoes, no unico ponto do sistema
-- onde a taxa de escrita importa. A integridade fica por conta da aplicacao --
-- o lote e gravado antes dos frames, na mesma transacao. Troca consciente de
-- garantia declarativa por velocidade de escrita, e o motivo esta escrito aqui,
-- que e o que separa isso de esquecimento.

-- SURPRESA 3 -- a janela de 1 dia e decisao de projeto, nao padrao.
-- O raciocinio vem do dominio: uma sessao de teste cabe num dia, e toda consulta
-- e escopada por sessao -- entao a consulta tipica toca exatamente um chunk.
-- Medido no docs/06 §8.2: 2h de pista geram um chunk por tabela.
SELECT create_hypertable('raw_frame', 'frame_time',
                         chunk_time_interval => INTERVAL '1 day');

-- Atende `WHERE session_id = ? AND frame_time BETWEEN ? AND ?`.
-- A coluna mais seletiva vem primeiro: a ordem nao e arbitraria.
CREATE INDEX idx_raw_session_time ON raw_frame (session_id, frame_time DESC);
CREATE INDEX idx_raw_batch        ON raw_frame (batch_id);

-- Compressao: agrupa fisicamente as linhas que compartilham session_id e can_id,
-- e dentro desses grupos quase tudo se repete. Medido: 40x (928 MB -> 23 MB) em
-- dado sintetico -- tratar como teto, dado real comprime menos.
ALTER TABLE raw_frame SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'session_id, can_id',
    timescaledb.compress_orderby   = 'frame_time DESC'
);

-- Comprime dado com mais de 30 dias. NAO existe politica de retencao aqui:
-- apagar o cru elimina a capacidade de reprocessar que o ADR-001 comprou.
SELECT add_compression_policy('raw_frame', INTERVAL '30 days');

COMMENT ON TABLE  raw_frame            IS 'Frame CAN cru, imutavel, append-only. Fonte da '
                                          'verdade (ADR-001): nunca apagar, so comprimir.';
COMMENT ON COLUMN raw_frame.frame_time IS 'Relogio do DISPOSITIVO. E por esta coluna que o chunk '
                                          'e escolhido -- um lote que chega as 11h com carimbo '
                                          'de 9h vai para a gaveta das 9h (ADR-004).';
COMMENT ON COLUMN raw_frame.received_at IS 'Relogio do SERVIDOR. Ancora para corrigir a deriva '
                                          'do relogio do ESP32 depois.';
COMMENT ON COLUMN raw_frame.payload    IS 'BYTEA e nao BIGINT: 8 bytes cabem num BIGINT, mas isso '
                                          'perderia o DLC -- 3 bytes e 8 bytes com zeros a '
                                          'esquerda viram o mesmo numero.';
