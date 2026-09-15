# 10 · Requisitos não-funcionais

Os números-alvo do sistema, **cada um com o método de medição ao lado**. Sem isso, o teste de
carga da Fase 5 não teria critério — "parece rápido" não é resultado.

> **Regra deste documento:** toda meta ou é **derivada do domínio** (com a conta à vista), ou é
> **medida** (com o comando que produziu o número). Nada é chutado.

---

## 1. Ingestão

### 1.1 A meta não é o regime permanente — é a rajada

O instinto seria dimensionar pelos 500 frames/s que o carro produz. **Está errado**, e entender
por quê é entender o sistema.

Enquanto o carro está na pista, ele não manda nada — está fora do WiFi, empilhando no cartão. O
servidor fica **ocioso**. Quando o carro volta ao box, tudo chega de uma vez.

```
Na pista (2 h)        Volta ao box
─────────────────►    ═══════════►
   0 req/s            TUDO de uma vez
   (buffer no SD)     3,6 milhões de frames
```

Então a pergunta certa é: **em quanto tempo o buffer precisa drenar?**

A resposta vem da equipe, não da técnica: o dado precisa estar consultável **antes da próxima
tanda**, ou seja, uns 5 minutos. Daí sai a conta:

```
3.600.000 frames ÷ 300 s  =  12.000 frames/s
```

| Métrica | Alvo | De onde vem |
|---|---|---|
| **Throughput de ingestão sustentado** | **≥ 12.000 frames/s** | Drenar 2 h de buffer em ≤ 5 min |
| Regime permanente (1 carro ao vivo) | 500 frames/s | Taxa do barramento — trivial perto da rajada |
| Pico absoluto (4 nós despejando juntos) | 48.000 frames/s | Meta de esforço, não de garantia |

**Como medir (Fase 5):** o gerador sintético do [ADR-005](02-decisoes-tecnicas.md) despeja 2 h de
sessão o mais rápido que conseguir, e cronometra-se o tempo total até o último `200 OK`.

### 1.2 Latência do `POST /ingest`

A latência não é meta estética — ela **limita o throughput**. Um ESP32 que envia em série só
consegue `tamanho_do_lote ÷ latência` frames por segundo.

```
lote de 1.000 frames, p95 de 400 ms  →   2.500 frames/s   ❌ não atinge a meta
lote de 5.000 frames, p95 de 400 ms  →  12.500 frames/s   ✅
```

| Métrica | Alvo | Observação |
|---|---|---|
| **p95 do `POST /ingest`, lote de 5.000** | **≤ 400 ms** | É o que sustenta 12.000 frames/s em cliente sequencial |
| p99 | ≤ 1 s | |
| Timeout do lado do firmware | 10 s | Acima disso, backoff e retentar com o mesmo `batchId` |

> **Se a meta não for atingida**, a saída registrada é migrar a decodificação para assíncrona
> ([`docs/07 §5`](07-arquitetura-do-codigo.md)) — a coluna `decoded_at` já existe para isso, então
> é mudança de código e não de schema.

---

## 2. Consulta — **medido**, não estimado

Os números abaixo saíram de uma prova de enduro de 4 h carregada num container real:
**18 milhões de pontos de sinal, 3.145 MB**, em PostgreSQL 17.11 com TimescaleDB 2.29.2.

### 2.1 O que foi medido

| Consulta | Sem agregado | **Com agregado contínuo** | Ganho |
|---|---|---|---|
| Métricas, janela de 5 min (dashboard ao vivo) | 38 ms | — | já rápido |
| **Resumo da sessão** (min/máx/média dos 6 sinais) | 1.864 ms | **22 ms** | **85×** |
| **Métricas, sessão inteira** em janelas de 1 s | 6.899 ms | **3,8 ms** | **1.800×** |

### 2.2 A conclusão que o número forçou

O `docs/06 §6` tinha deixado o agregado contínuo como *"talvez na Fase 3, decidir com o número em
mãos"*. **O número apareceu, e ele decide:**

**O agregado contínuo é obrigatório na Fase 3.** Sem ele, abrir o resumo de uma sessão custa
quase 2 segundos e o gráfico da sessão inteira custa 7 — ambos inaceitáveis para uso interativo
no box.

E o custo é irrisório: o agregado ocupa **14 MB** contra 3.145 MB da tabela bruta — **225× menor**,
porque 4 h de dado viram 14.400 janelas de 1 s por sinal, em vez de 18 milhões de pontos.

### 2.3 O que a medição também derrubou

Foi medido, e **não confirmou a expectativa**: o índice composto
`(session_id, signal_name, ts DESC)` rende só **1,4×** na agregação de sessão inteira
(9.463 ms → 6.899 ms), e **2×** na janela de 5 min (81 ms → 38 ms).

O `EXPLAIN` mostrou o motivo: o TimescaleDB **já cria sozinho** um índice em `ts`, e ele faz a
maior parte da poda. O índice composto ajuda, mas não é ele que resolve agregação de sessão
inteira — **é o agregado contínuo.**

> ⚠️ **Ressalva honesta:** a medição rodou com **uma única sessão** no banco, então
> `session_id = ?` casava com 100% das linhas — cenário que **subestima** o valor do índice. Com
> uma temporada inteira dentro, a seletividade sobe muito. Remedir na Fase 5 com várias sessões
> antes de considerar removê-lo.

### 2.4 As metas

| Métrica | Alvo | Situação hoje |
|---|---|---|
| `GET /sessions/{id}/summary` | ≤ 200 ms | ✅ **~70 ms** medido no endpoint real, com 18 M pontos |
| `GET /metrics`, janela ≤ 15 min | ≤ 100 ms | ✅ 38 ms |
| `GET /metrics`, sessão inteira | ≤ 500 ms | ✅ 3,8 ms **com** agregado · ❌ 6.899 ms sem |
| `GET /sessions` (lista) | ≤ 100 ms | ✅ **7 ms** com 2,16 M frames no banco — ver abaixo |

### 2.5 A listagem de sessões — e por que ela é barata

Medido no checkpoint 3.1: **7 ms** com 2.161.712 frames no banco.

O número não vem de otimização de consulta, e sim de **onde o agregado mora**. A listagem toca
**39 linhas** de `ingest_batch`, não 2,16 milhões de `raw_frame`:

| Origem | Linhas tocadas | Tempo |
|---|---|---|
| `raw_frame` | 2.200.000 | 220 ms — **estoura a meta** |
| `ingest_batch` | 36 | 1,5 ms |

E a diferença cresce: `raw_frame` acompanha a temporada, `ingest_batch` acompanha o número de
lotes. Ver [ADR-011](02-decisoes-tecnicas.md).

**Como medir:** `\timing on` no `psql` contra dado do gerador sintético, e depois `EXPLAIN
(ANALYZE, BUFFERS)` em qualquer consulta que estourar a meta.

---

## 3. Armazenamento

Medido nos mesmos containers ([`docs/06 §8`](06-modelo-de-dados.md)):

| Cenário | `raw_frame` | `signal_point` | Total |
|---|---|---|---|
| Teste de 2 h (3,6 M frames) | 928 MB | 1.675 MB | ~2,5 GB |
| Enduro de 4 h (7,2 M frames) | ~1,9 GB | 3.145 MB | ~5 GB |

**Com compressão, o `raw_frame` cai 40×** — 928 MB viram 23 MB (medido). Tratar como **teto**: o
payload sintético é mais regular que telemetria real, então remedir na Fase 5 com o gerador.

| Métrica | Alvo |
|---|---|
| Crescimento por temporada (~30 sessões) | ≤ 150 GB sem compressão · **estimado ≤ 40 GB** com |
| Compressão de `raw_frame` após 30 dias | ≥ 10× (medido 40× em dado sintético) |
| Retenção de `raw_frame` | **infinita** — nunca apagar ([ADR-001](02-decisoes-tecnicas.md)) |
| Retenção de `signal_point` | descartável — reconstruível a partir do cru |

---

## 4. Correção e durabilidade

Estes não têm número: são **binários**, e valem mais que qualquer meta de desempenho.

| Requisito | Por quê |
|---|---|
| **Nunca responder 2xx para dado não comitado** | O ESP32 apaga o cartão ao ver 2xx. Um 2xx mentiroso destrói o dado de forma irrecuperável |
| **Nunca duplicar um lote reenviado** | Dado duplicado não gera erro — gera média errada e ninguém percebe ([ADR-008](02-decisoes-tecnicas.md)) |
| **Nunca decodificar em silêncio com mapa desconhecido** | Diretiva DBC não suportada derruba a subida, não é ignorada ([ADR-006](02-decisoes-tecnicas.md)) |
| **Valor fora de faixa é marcado, nunca descartado** | Um buraco no gráfico não distingue "sensor morreu" de "carro parado" |

**Como verificar:** são os casos de teste do [`docs/09`](09-estrategia-de-testes.md), não medições
de carga.

---

## 5. O que NÃO é requisito aqui

**Alta disponibilidade.** Se a API cair, o ESP32 recebe erro retentável, segura o buffer e tenta
de novo. O cartão SD **é** o mecanismo de tolerância a falhas, e ele guarda horas. Perseguir
99,9% de uptime seria resolver um problema que o desenho já resolve.

**Latência de milissegundo.** Isto não é telemetria ao vivo para o piloto — para isso existe o
display TFT, que continua funcionando. Aqui é análise **depois** da tanda.

**Escala horizontal.** Um servidor atende quatro nós de um carro. Projetar para múltiplas
instâncias seria complexidade sem problema correspondente — e a resposta honesta a *"por que isso
é distribuído?"* seria "porque achei que ficaria bonito".
