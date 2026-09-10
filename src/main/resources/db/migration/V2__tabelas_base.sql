-- Tabelas que NAO sao serie temporal: poucas linhas, sem particionamento.
-- As duas hypertables (raw_frame e signal_point) entram na V3.
-- Modelo completo e justificativas: docs/06-modelo-de-dados.md

-- ---------------------------------------------------------------------------
-- session: um teste, uma bateria, uma prova
-- ---------------------------------------------------------------------------
CREATE TABLE session (
    id           TEXT PRIMARY KEY,
    description  TEXT,
    started_at   TIMESTAMPTZ,
    ended_at     TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  session      IS 'Periodo continuo de coleta. Unidade natural de consulta.';
COMMENT ON COLUMN session.id   IS 'Gerado pelo DISPOSITIVO, formato AAAA-MM-DD-slug (ADR-007). '
                                  'TEXT e nao UUID porque a lista de sessoes precisa ser legivel.';
COMMENT ON COLUMN session.started_at IS 'Derivado: menor frame_time visto. Nulo ate o primeiro lote.';
COMMENT ON COLUMN session.ended_at   IS 'Derivado: maior frame_time visto.';

-- ---------------------------------------------------------------------------
-- ingest_batch: um lote recebido do ESP32. E a idempotencia inteira (ADR-008)
-- ---------------------------------------------------------------------------
CREATE TABLE ingest_batch (
    id              UUID PRIMARY KEY,
    session_id      TEXT NOT NULL REFERENCES session (id),
    device_id       TEXT NOT NULL,
    frame_count     INTEGER NOT NULL,
    rejected_count  INTEGER NOT NULL DEFAULT 0,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    decoded_at      TIMESTAMPTZ
);

CREATE INDEX idx_batch_session ON ingest_batch (session_id, received_at DESC);

COMMENT ON TABLE  ingest_batch     IS 'Um lote recebido. A chave primaria E a protecao contra '
                                      'duplicacao: o banco recusa o mesmo batchId duas vezes, e '
                                      'nenhuma corrida entre requisicoes simultaneas dribla isso.';
COMMENT ON COLUMN ingest_batch.id  IS 'O batchId gerado pelo dispositivo (ADR-008).';
COMMENT ON COLUMN ingest_batch.received_at IS 'Relogio do SERVIDOR. O do dispositivo vai em '
                                      'raw_frame.frame_time -- sao horarios diferentes, e podem '
                                      'separar minutos se o carro estava fora do WiFi (ADR-004).';
COMMENT ON COLUMN ingest_batch.decoded_at IS 'Nulo = cru gravado, sinais ainda nao. Permite '
                                      'reprocessar sem adivinhar e sobreviver a crash no meio.';
COMMENT ON COLUMN ingest_batch.rejected_count IS 'Frames descartados por serem ilegiveis (ADR-010). '
                                      'Se este numero subir numa sessao, o problema e fisico: '
                                      'cartao SD ruim, alimentacao instavel.';

-- ---------------------------------------------------------------------------
-- signal_definition: snapshot do DBC vigente
-- ---------------------------------------------------------------------------
CREATE TABLE signal_definition (
    dbc_version   TEXT     NOT NULL,
    can_id        INTEGER  NOT NULL,
    signal_name   TEXT     NOT NULL,
    start_bit     SMALLINT NOT NULL,
    bit_length    SMALLINT NOT NULL,
    byte_order    TEXT     NOT NULL CHECK (byte_order IN ('BIG', 'LITTLE')),
    is_signed     BOOLEAN  NOT NULL,
    scale         DOUBLE PRECISION NOT NULL,
    offset_value  DOUBLE PRECISION NOT NULL,
    min_value     DOUBLE PRECISION,
    max_value     DOUBLE PRECISION,
    unit          TEXT,
    PRIMARY KEY (dbc_version, can_id, signal_name)
);

COMMENT ON TABLE  signal_definition IS 'Registro historico de qual mapa de sinais estava vigente '
                                       'quando. O arquivo .dbc continua sendo a fonte de verdade '
                                       '(ADR-006); esta tabela responde "com que mapa este numero '
                                       'foi calculado?".';
COMMENT ON COLUMN signal_definition.start_bit IS 'Na numeracao do DBC: em BIG endian aponta o bit '
                                       'MAIS significativo do sinal, em LITTLE o menos (docs/05).';
COMMENT ON COLUMN signal_definition.offset_value IS 'Chama-se offset_value e nao offset porque '
                                       'OFFSET e palavra reservada no SQL.';
