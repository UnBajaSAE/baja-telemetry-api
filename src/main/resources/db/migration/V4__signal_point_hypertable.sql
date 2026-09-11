-- signal_point: o sinal ja decodificado. E a tabela CONSULTADA -- e a maior.
--
-- A assimetria do docs/01 §2 aparece aqui: 1.000 frames viram ~2.500 pontos,
-- porque cada frame carrega varios sinais.
--
-- Ao contrario de raw_frame, esta tabela e DESCARTAVEL: pode ser apagada e
-- reconstruida a partir do cru. E o outro lado do ADR-001.

CREATE TABLE signal_point (
    ts           TIMESTAMPTZ NOT NULL,
    session_id   TEXT        NOT NULL,
    signal_name  TEXT        NOT NULL,
    value        DOUBLE PRECISION NOT NULL,
    is_valid     BOOLEAN     NOT NULL DEFAULT TRUE,
    can_id       INTEGER     NOT NULL,
    dbc_version  TEXT        NOT NULL
);

-- Mesma janela do raw_frame: uma sessao cabe num chunk (dia UTC).
SELECT create_hypertable('signal_point', 'ts',
                         chunk_time_interval => INTERVAL '1 day');

-- Atende exatamente o GET /sessions/{id}/metrics?signal=rpm&from=&to=
-- A ordem nao e arbitraria: a coluna mais seletiva vem primeiro.
CREATE INDEX idx_signal_query ON signal_point (session_id, signal_name, ts DESC);

COMMENT ON TABLE  signal_point             IS 'Sinal decodificado. Derivada e reconstruivel a '
                                              'partir de raw_frame -- pode ser apagada (ADR-001).';
COMMENT ON COLUMN signal_point.is_valid    IS 'false = fora da faixa do DBC. O ponto e GRAVADO e '
                                              'marcado, nao descartado: um buraco no grafico nao '
                                              'distingue "sensor morreu" de "carro parado".';
COMMENT ON COLUMN signal_point.dbc_version IS 'Com que versao do mapa este numero foi calculado. '
                                              'Sem isso, depois de corrigir uma escala ninguem '
                                              'sabe quais pontos ja foram reprocessados.';
COMMENT ON COLUMN signal_point.signal_name IS 'TEXT repetido milhoes de vezes, de proposito: a '
                                              'compressao do Timescale lida bem com repeticao, e '
                                              'consulta legivel sem JOIN vale enquanto o projeto '
                                              'e pequeno. Revisar na Fase 5 (docs/06 §8.4).';
