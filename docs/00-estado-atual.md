# 00 · Estado atual — onde retomar

> **Atualize este arquivo ao fechar cada checkpoint.** É o primeiro que o Claude lê ao
> retomar o trabalho, e o que evita recomeçar o contexto do zero a cada sessão.

**Última atualização:** 10/09/2026 — checkpoint 2.5 fechado

---

## Fase atual

**🔵 Fase 2 — Modelo de dados e decodificador (5/6).** **O sistema grava.** O dado do carro entra pelo `/ingest`, é decodificado e fica no banco.

**✅ Fase 1 — Esqueleto: COMPLETA (5/5).** A API sobe, o banco roda no Compose, e o `/ingest` já aceita o contrato — mas **ainda não grava nada**.

**✅ Fase 0 — Fundação documental: COMPLETA.** As decisões de domínio, dados, arquitetura,
contratos e qualidade estão registradas, e as verificáveis foram verificadas contra ferramenta
real. **A próxima sessão começa a Fase 1 — a primeira linha de Kotlin.**

> 📍 **O mapa completo de todas as fases e checkpoints está em
> [`docs/12-plano-de-fases.md`](12-plano-de-fases.md).** Este arquivo aqui é só "onde eu estava".

| Checkpoint | Escopo | Estado |
|---|---|---|
| 0.1 | Congelar o domínio — DBC, mapa de sinais, ADR-006 | ✅ **fechado** |
| 0.2 | Congelar o modelo de dados — schema, ADR-007, ADR-008 | ✅ **fechado** |
| 0.3 | Congelar arquitetura de código e contratos — ADR-009, ADR-010, erros, OpenAPI | ✅ **fechado** |
| 0.4 | Congelar qualidade e execução — testes, RNFs, setup | ✅ **fechado** |
| 0.5 | Skills e amarração final | ✅ **fechado** |

## O que já existe

- [x] Repositório criado em `UnBajaSAE/baja-telemetry-api` (público)
- [x] README com problema, arquitetura, stack e roadmap
- [x] `docs/01` — domínio CAN documentado
- [x] `docs/02` — 10 ADRs registrados
- [x] `docs/03` — contrato de ingestão esboçado
- [x] `docs/04` — glossário
- [x] `docs/05` — o que é DBC e por quê, numeração de bits, template de levantamento
- [x] `docs/06` — modelo de dados, verificado contra um TimescaleDB real
- [x] `docs/07` — arquitetura de pacotes, com o fluxo de um POST passo a passo
- [x] `docs/08` — catálogo de erros e quando o firmware retenta
- [x] `docs/09` — estratégia de testes, com o teste de propriedade explicado
- [x] `docs/10` — requisitos não-funcionais, derivados ou **medidos**
- [x] `docs/11` — versões fixadas (JDK 25 · Kotlin 2.4.10 · Spring Boot 4.1.1 · PG 17)
- [x] `docs/12` — mapa de checkpoints de todas as fases
- [x] `docs/13` — o caminho de um lote, em diagramas (**porta de entrada do repo**)
- [x] `contracts/can/unbaja.dbc` — DBC **provisório**, validado com `cantools`
- [x] `contracts/openapi.yaml` — validado com `openapi-spec-validator`
- [x] `CLAUDE.md` e 4 skills em `.claude/skills/`
- [x] **Projeto Gradle** — Kotlin 2.4.10 · Spring Boot 4.1.1 · JDK 25 · Gradle 9.7.1
- [x] `/actuator/health` respondendo `UP` na porta **8081**, com teste que o afirma
- [x] **`docker-compose.yml`** — PostgreSQL 17.11 + TimescaleDB 2.29.2, `healthy` em ~6 s
- [x] **`POST /api/v1/ingest`** validando o contrato, com Problem Details e `retryable`
- [x] Domínio puro (`CanFrame`, `SessionId`) — 15 testes em **0,04 s**, sem Spring
- [x] Teste de arquitetura (ArchUnit) fiscalizando o ADR-009
- [x] **Testcontainers** — PostgreSQL 17 + TimescaleDB real na suíte, **um container para a suíte**
- [x] **Gerador sintético** (`./gradlew gerador`) — 111 frames/s, curvas verificadas com `cantools`
- [x] **`FrameEncoder`** no domínio — bate byte a byte com o `cantools` nos três frames
- [x] **Flyway** — `session`, `ingest_batch` e `signal_definition` no banco (V1, V2)
- [x] **`raw_frame` como hypertable** (V3) — chunks de 1 dia, índices e compressão em 30 dias
- [x] **Parser do DBC** — lê `contracts/can/unbaja.dbc`, falha alto no que não suporta
- [x] **Zero sinais hardcodados** — a verdade voltou a morar só no arquivo (ADR-006)
- [x] **Decodificador** — duas propriedades + as três armadilhas, provado capaz de falhar
- [x] **Persistência em lote** (V4) — o `/ingest` grava cru e decodificado, numa transação
- [x] **13,9× medido** entre inserção em lote e linha a linha

## O que NÃO existe ainda

- [x] ~~Projeto Gradle / código Kotlin~~ — existe e sobe (checkpoint 1.1)

- [ ] **DBC com os sinais reais** — o atual é fictício (ver abaixo)
- [ ] Gerador de dados sintéticos
- [ ] Consulta: não há endpoint para ler o que foi gravado (Fase 3)
- [ ] API key, rate limiting, deploy (Fases 4 e 5)

---

## Próximo passo

**Checkpoint 2.6 — idempotência ponta a ponta.** Último da Fase 2.

**Aceite:** enviar o mesmo lote duas vezes devolve 200 com `duplicate: true` na segunda, e
`SELECT count(*)` prova que nada duplicou.

O mecanismo já existe — o `JdbcIngestBatchStore` usa `ON CONFLICT DO NOTHING` e devolve `false`
quando o `batchId` repete. Falta o teste de ponta a ponta, e rodar o gerador com `--reenviar`
para ver o aviso dele **parar de aparecer**: hoje ele denuncia que os frames entraram duas vezes
e a API não percebeu.

### Como rodar o que já existe

```bash
docker compose up -d                       # sobe o banco
./gradlew bootRun                          # sobe a API na 8081
./gradlew test                             # 74 testes
./gradlew gerador                          # telemetria sintetica -- agora GRAVA
```

Para ver o que foi gravado:

```sql
SELECT signal_name, count(*), round(avg(value)::numeric, 1) AS media
FROM signal_point GROUP BY signal_name ORDER BY 1;
```

### Pendência paralela do Heitor: levantar os sinais reais

O bloqueador nº 1 foi **destravado**, não resolvido: o DBC existe e é válido, mas os valores são
fictícios. O desenvolvimento pode seguir em cima dele sem retrabalho — o DBC é dado de entrada,
não código.

Quando houver tempo, levantar do firmware do ESP32 e preencher a tabela-template em
[`docs/05 §5.1`](05-mapa-de-sinais.md): para cada sinal, o frame, a ECU, bit inicial, largura,
endianness, signed, escala, offset, faixa e unidade. **Informe também qual convenção de
numeração de bit o firmware usa** — a conversão para a do DBC é mecânica, mas depende disso.

---

## Decisões em aberto

| Questão | Status |
|---|---|
| `sessionId` criado pelo dispositivo ou pela API? | ✅ **fechado: dispositivo** (ADR-007) |
| Granularidade da idempotência | ✅ **fechado: por lote** (ADR-008) |
| Correção de deriva do relógio do ESP32 | schema já guarda os dois relógios; falta o algoritmo |
| `session_id`/`signal_name` como `TEXT` incham os índices (medido: 731 MB) | **revisar na Fase 5** com o gerador realista ([`docs/06 §8.4`](06-modelo-de-dados.md)) |
| O índice composto rende só 1,4× — medido com **uma só sessão** no banco | remedir na Fase 5 com a temporada inteira ([`docs/10 §2.3`](10-requisitos-nao-funcionais.md)) |
| Catálogo de erros e retentativa do firmware | ✅ **fechado** ([`docs/08`](08-contrato-de-erros.md)) |
| Hospedagem: o Postgres gerenciado escolhido suporta TimescaleDB? | **verificar antes da Fase 5** |
| Rotação de API key por dispositivo | em aberto |

---

## Histórico de sessões

### 24/08/2026 — Fundação
Repositório criado. Documentação de domínio, ADRs e contrato de ingestão escritos antes de
qualquer código, para que as decisões fiquem registradas com o porquê — é o que será lido
numa entrevista.

### 24/08/2026 — Checkpoint 0.1: domínio congelado
DBC provisório criado em `contracts/` (e não em `src/`, porque é contrato compartilhado entre
firmware, API e ferramentas de bancada). Os três frames materializam, cada um, uma das armadilhas
do `docs/01 §4` — sinal cruzando fronteira de byte, signed em complemento de dois, e o caso base.

Validado com `cantools`: `3E 80 5B` → 4.000 rpm e 51 °C, batendo com o exemplo do `docs/01`.

**Correção:** o trecho de DBC no `docs/01 §5` estava errado — usava `0|16@1+` enquanto o texto
afirmava big endian. `@1` é little endian, e em big endian o bit inicial aponta o MSB (7, não 0).
Provado com a ferramenta: a linha antiga decodifica o mesmo payload como 8.207,5 rpm. É
literalmente a armadilha que a seção 4 do próprio documento descreve.

### 24/08/2026 — Checkpoint 0.2: modelo de dados congelado
Cinco tabelas, duas delas hypertables. O DDL foi **extraído do próprio `docs/06` e executado**
num TimescaleDB real: hypertables criadas, chunks nascendo um por dia, e o planejador podando os
chunks irrelevantes numa consulta escopada por sessão.

Três surpresas do TimescaleDB documentadas com o erro real: hypertable recusa chave primária que
não inclua a coluna de particionamento; chave estrangeira custa uma verificação por linha
inserida; e o tamanho do chunk é decisão de projeto (escolhido 1 dia, porque uma sessão cabe num).

ADR-007 (sessão gerada pelo dispositivo) e ADR-008 (idempotência por lote) fechados. Os dois
geraram **requisitos de firmware** — gravar `batchId` e `sessionId` no cartão SD, não só em
memória — registrados no `docs/03` para a equipe de eletrônica.

### 25/08/2026 — Medição do modelo de dados
Simulado um sábado de 2 h de pista (3,6 M frames, 7,2 M pontos de sinal) num TimescaleDB real,
para responder concretamente "o que cabe num chunk": **uma sessão inteira cabe em um chunk por
tabela**, ~2,5 GB no total. Compressão mediu fator **40×** (928 MB → 23 MB) — tratar como teto,
porque dado sintético comprime melhor que o real.

**Achado não previsto:** os índices ficaram maiores que os dados (731 MB só o `idx_signal_query`),
por causa do `session_id` em `TEXT` replicado por linha dentro do índice. É o gatilho da revisão
que a `docs/06 §3.4` já previa "com número medido" — agora há número. Não muda nada agora;
2,5 GB por sessão é administrável.

Registrado no anexo [`docs/06 §8`](06-modelo-de-dados.md), com a linha do tempo de um dia de teste
do box até a consulta.

### 25/08/2026 — Checkpoint 0.3: arquitetura e contratos congelados
Duas decisões do Heitor: **domínio isolado do framework** (ADR-009) e **decodificação síncrona
na mesma transação** (`docs/07 §4`). A regra "o pacote `domain` não importa nada de Spring" existe
por um motivo concreto — o teste de propriedade do decodificador precisa rodar em milissegundos,
e teste lento vira teste não executado. Vai ser fiscalizada por teste de arquitetura (ArchUnit).

`docs/07` traz o fluxo de um `POST /ingest` em 14 passos, com a coluna "o que essa camada **não**
sabe" — que é onde as fronteiras ficam visíveis.

`docs/08` é o documento que a equipe de eletrônica consome: as 15 situações previstas, cada uma
com **`retryable`** dizendo se o firmware apaga o buffer ou guarda. O campo é extensão nossa à
RFC 7807, para que o firmware não precise embutir a tabela de códigos HTTP.

**ADR-010 nasceu durante o trabalho:** um bit invertido no cartão SD não pode derrubar 999 frames
bons. Aceitação parcial, com a perda auditável na coluna nova `ingest_batch.rejected_count`.

### 25/08/2026 — Documento visual do fluxo
Criado o `docs/13-o-caminho-de-um-lote.md` a pedido do Heitor, depois que a tabela de 14 passos
do `docs/07` não comunicou bem. Seis diagramas mermaid: o fluxo completo em sequência, a
geografia física (o que roda na JVM e o que roda no Postgres), a transação, a idempotência quando
o WiFi cai na hora errada, o porquê de gravar o cru primeiro, e o carimbo que decide o chunk.

Todos os 11 diagramas mermaid do repositório foram validados com o parser oficial do mermaid —
diagrama quebrado renderiza como bloco de erro no GitHub, e este repo é público.

O `docs/07 §4` também foi reescrito em prosa, e ganhou a §3 com a geografia física.

### 25/08/2026 — Checkpoint 0.4: qualidade e requisitos congelados
Stack fixada pelo Heitor: **JDK 25 · Kotlin 2.4.10 · Spring Boot 4.1.1 · PostgreSQL 17**, versões
conferidas nos repositórios oficiais. O README dizia "Spring Boot 3" e foi atualizado; o trade-off
(menos tutoriais cobrindo a linha 4) está registrado no `docs/11 §1.1`.

A meta de ingestão é **derivada do domínio**, não chutada: o carro não manda nada enquanto está na
pista, então o que dimensiona não é o regime permanente de 500 frames/s — é a **rajada** quando o
buffer de 2 h drena de uma vez. 3,6 M frames em 5 minutos → **12.000 frames/s**.

As metas de consulta foram **medidas** numa prova de enduro de 4 h (18 M pontos, 3.145 MB) em
PG 17.11 / TimescaleDB 2.29.2. Dois resultados:

- **O agregado contínuo deixou de ser "talvez" e virou obrigatório**: a mesma consulta cai de
  6.899 ms para **3,8 ms**, e o agregado ocupa 14 MB contra 3.145 MB. O `docs/06 §6` foi corrigido.
- **A expectativa sobre o índice composto foi derrubada:** rende só 1,4×, porque o TimescaleDB já
  cria um índice em `ts` sozinho. Com a ressalva de que a medição tinha uma só sessão no banco, o
  que subestima o índice — remedir na Fase 5.
