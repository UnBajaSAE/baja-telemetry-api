-- A imagem timescale/timescaledb ja traz a extensao compilada, mas cada banco
-- precisa habilita-la. Fazer isso a mao funcionaria no laptop e falharia no CI,
-- onde o banco nasce vazio a cada execucao.
CREATE EXTENSION IF NOT EXISTS timescaledb;
