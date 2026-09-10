# 12 · Plano de fases e checkpoints

**O mapa de progresso do projeto.** O README lista as cinco fases em uma linha cada; aqui cada
fase vira checkpoints pequenos, com **critério de aceitação verificável**.

> **Critério verificável** significa um comando que se roda e uma saída que se observa —
> *"`curl` no `/ingest` devolve 200 com `framesStored: 2`"*, não *"endpoint funcionando"*.

**Regra:** não avançar de fase sem fechar a anterior. O valor deste projeto está na
profundidade, não na quantidade de features.

**Legenda:** ✅ fechado · 🔵 em andamento · ⬜ não começado · ⏸️ bloqueado

---

## Panorama

| Fase | Escopo | Checkpoints | Estado |
|---|---|---|---|
| **0** | Fundação documental | 5 | ✅ **5/5** |
| **1** | Esqueleto — sobe, recebe, testa | 5 | 🔵 2/5 |
| **2** | Modelo de dados e decodificador | 6 | ⬜ 0/6 |
| **3** | Consulta | 4 | ⬜ 0/4 |
| **4** | Robustez | 5 | ⬜ 0/5 |
| **5** | Deploy e medição | 4 | ⬜ 0/4 |

> As fases 3 a 5 estão propositalmente menos detalhadas: os critérios dependem de decisões que
> ainda não foram tomadas. Cada fase é refinada ao fechar a anterior.

---

## Fase 0 — Fundação documental

*Escrever as decisões antes do código, para que a implementação seja execução e não descoberta.*

### ✅ 0.1 · Congelar o domínio
Produz `contracts/can/unbaja.dbc`, `docs/05-mapa-de-sinais.md`, ADR-006.
**Aceite:** `pipx run cantools dump contracts/can/unbaja.dbc` parseia sem erro, e o payload
`3E805B0000000000` decodifica para 4.000 rpm / 51 °C, batendo com o `docs/01`.
**Fechado em 24/08/2026.** Bônus: corrigiu um erro de endianness no `docs/01 §5`.

### ✅ 0.2 · Congelar o modelo de dados
Produz `docs/06-modelo-de-dados.md`, ADR-007 (sessão gerada pelo dispositivo), ADR-008
(idempotência na granularidade do lote).
**Aceite:** o DDL foi extraído do próprio documento e executado num container
`timescale/timescaledb:latest-pg16` — as duas hypertables criadas, chunks nascendo um por dia, e
o planejador podando os chunks irrelevantes.
**Fechado em 24/08/2026.** Gerou dois requisitos de firmware, registrados no `docs/03`.

### ✅ 0.3 · Congelar arquitetura de código e contratos
Produz `docs/07-arquitetura-do-codigo.md`, `docs/08-contrato-de-erros.md`,
`contracts/openapi.yaml`, ADR-009 (camadas) e ADR-010 (aceitação parcial); atualiza `docs/03`
e `docs/06`.
**Aceite:** `pipx run openapi-spec-validator contracts/openapi.yaml` → **OK**; o catálogo de
erros tem `retryable` definido para as 15 situações previstas.
**Fechado em 25/08/2026.** Gerou o ADR-010 e a coluna `rejected_count` no schema.

### ✅ 0.4 · Congelar qualidade e requisitos
Produz `docs/09-estrategia-de-testes.md`, `docs/10-requisitos-nao-funcionais.md`,
`docs/11-ambiente-e-setup.md`.
**Aceite:** o `docs/10` tem alvo **e** método para cada métrica — a meta de ingestão é *derivada*
(drenar 2 h de buffer em 5 min → 12.000 frames/s) e as de consulta são *medidas* numa prova de
enduro de 4 h com 18 M pontos.
**Fechado em 25/08/2026.** A medição decidiu o agregado contínuo (6.899 ms → 3,8 ms) e derrubou
a expectativa sobre o índice composto (só 1,4×).

### ✅ 0.5 · Skills e amarração
Produz as skills `registrar-adr`, `kotlin-spring`, `revisar-decodificador`; atualiza `README`,
`CLAUDE.md`, `docs/00`, `docs/04`.
**Aceite:** as três skills carregam; DBC válido no `cantools`; OpenAPI válido; DDL roda em
TimescaleDB; 11 diagramas mermaid parseiam; zero links quebrados.
**Fechado em 25/08/2026. Fase 0 completa — a Fase 1 pode começar.**

---

## Fase 1 — Esqueleto

*Menor caminho de ponta a ponta: a aplicação sobe, recebe um POST, e existe um teste de verdade.*

> **A Fase 1 começa aqui.** Versões e comandos em [`docs/11`](11-ambiente-e-setup.md);
> estrutura de pacotes em [`docs/07 §2`](07-arquitetura-do-codigo.md).

### ✅ 1.1 · Projeto Gradle + Spring Boot sobe
**Aceite:** `./gradlew bootRun` sobe, e `curl localhost:8081/actuator/health` devolve
`{"status":"UP"}` — verificado com a aplicação real, mais um teste `@SpringBootTest` que afirma
o mesmo e que foi provado capaz de falhar.
**Fechado em 09/09/2026.** Porta 8081 porque a 8080 já é usada por outro projeto na máquina.
Corrigiu de quebra um bug no `.gitignore` que impedia o `gradle-wrapper.jar` de ser versionado.

### ✅ 1.2 · Postgres + TimescaleDB via Compose
**Aceite:** `docker compose up -d` deixa o container `healthy` em ~6 s, e `psql` do host pela
porta publicada devolve `timescaledb 2.29.2` sobre PostgreSQL 17.11.
**Fechado em 10/09/2026.** O volume foi verificado de fato: tabela gravada sobreviveu a um
`down` + `up`. A porta é amarrada em `127.0.0.1` para o banco não ficar exposto na rede.

### ⬜ 1.3 · `POST /api/v1/ingest` aceita o contrato
Ainda **sem persistir** — valida a forma e devolve a resposta.
**Aceite:** `curl` com o corpo de exemplo do `docs/03` devolve 200 com `framesReceived: 2`;
corpo malformado devolve 400.

### ⬜ 1.4 · Primeiro teste com Testcontainers
**Aceite:** `./gradlew test` sobe um Postgres real, conecta e passa. O teste falha se o
container não subir — nunca cai em banco em memória por baixo (ADR-003).

### ⬜ 1.5 · Gerador de dados sintéticos
Pré-requisito, não extra (ADR-005). Curva de RPM plausível, temperatura subindo, GPS num traçado.
**Aceite:** o gerador alimenta o `/ingest` por 60 s sem o carro presente; e sabe simular queda de
conexão com reenvio do **mesmo** `batchId`.

---

## Fase 2 — Modelo de dados e decodificador

*O coração do sistema. É aqui que erro não gera exceção, gera dado silenciosamente errado.*

### ⬜ 2.1 · Flyway e primeiras tabelas
**Aceite:** `flyway_schema_history` mostra a V1 aplicada; subir a aplicação duas vezes não
reaplica migration.

### ⬜ 2.2 · `raw_frame` como hypertable
**Aceite:** `SELECT * FROM timescaledb_information.chunks` mostra chunks criados após inserir
dado que cruze a janela configurada.

### ⬜ 2.3 · Parser do DBC
**Aceite:** parseia `contracts/can/unbaja.dbc` produzindo 3 mensagens e 6 sinais; e **falha alto**
diante de diretiva não suportada, em vez de ignorar (ADR-006).

### ⬜ 2.4 · Decodificador de frame
**Aceite:** teste de propriedade `decode(encode(x)) ≈ x` passa com centenas de casos aleatórios
nos **três** frames — incluindo o sinal que cruza fronteira de byte e o signed. Mais: valor fora
da faixa do DBC é marcado inválido, não descartado em silêncio.

### ⬜ 2.5 · Persistência em lote
**Aceite:** medir inserção linha a linha versus em lote e registrar os dois números. Confirmar
que `rewriteBatchedStatements=true` está ativo — sem a flag o driver ignora o batch em silêncio
(ADR-004).

### ⬜ 2.6 · Idempotência ponta a ponta
**Aceite:** enviar o mesmo lote duas vezes devolve 200 com `duplicate: true` na segunda, e
`SELECT count(*)` prova que nada duplicou.

---

## Fase 3 — Consulta

### ⬜ 3.1 · `GET /sessions` — lista com duração e contagem
### ⬜ 3.2 · `GET /sessions/{id}/summary` — máximo, média e duração por sinal
### ⬜ 3.3 · `GET /sessions/{id}/metrics` — agregação por janela com `time_bucket`
⚠️ **Exige agregado contínuo** — decidido por medição, não por preferência ([`docs/10 §2.2`](10-requisitos-nao-funcionais.md)).
**Aceite:** sessão inteira em janelas de 1 s dentro de 500 ms; resumo da sessão dentro de 200 ms.
### ⬜ 3.4 · Paginação por cursor
**Aceite:** demonstrar que o tempo da página não cresce com a profundidade — o que aconteceria
com `OFFSET`.

---

## Fase 4 — Robustez

### ⬜ 4.1 · Problem Details (RFC 7807) em todos os erros
### ⬜ 4.2 · Validação de entrada conforme os limites do `docs/03`
### ⬜ 4.3 · Autenticação por API key
### ⬜ 4.4 · Rate limiting
### ⬜ 4.5 · Actuator e métricas de ingestão

---

## Fase 5 — Deploy e medição

### ⬜ 5.1 · Dockerfile multi-stage
**Aceite:** imagem final roda com JRE slim, sem Gradle nem código-fonte dentro.
### ⬜ 5.2 · CI no GitHub Actions — lint, teste, build
**Aceite:** o CI sobe Docker e roda os Testcontainers de verdade.
### ⬜ 5.3 · Deploy gerenciado
⏸️ **Bloqueado por decisão em aberto:** confirmar que o Postgres gerenciado escolhido suporta a
extensão TimescaleDB (ADR-002). Verificar **antes** de começar esta fase.
### ⬜ 5.4 · Teste de carga com número medido
**Aceite:** relatório com throughput sustentado, latência p95 e o gargalo identificado —
comparado contra as metas do `docs/10`. **Número medido, não estimado.**

---

## Como este documento é atualizado

Ao fechar um checkpoint: marcar ✅ aqui, e atualizar o [`docs/00-estado-atual.md`](00-estado-atual.md)
com o próximo passo. Os dois são complementares — este é o mapa inteiro, o `00` é "onde eu estava".
