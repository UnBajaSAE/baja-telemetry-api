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
| **1** | Esqueleto — sobe, recebe, testa | 5 | ✅ **5/5** |
| **2** | Modelo de dados e decodificador | 6 | 🔵 2/6 |
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

### ✅ 1.3 · `POST /api/v1/ingest` aceita o contrato
Ainda **sem persistir** — valida a forma e devolve a resposta.
**Aceite:** verificado com a aplicação real. O lote de exemplo devolve 200 com
`framesReceived: 2`; JSON quebrado devolve 400 e `sessionId` fora do formato devolve 422, ambos
em Problem Details com `retryable: false`. Um frame torto entre bons devolve 200 com
`framesRejected: 1` (ADR-010).
**Fechado em 10/09/2026.** 24 testes no total. Entrou o teste de arquitetura do ADR-009, provado
capaz de falhar.

> ⚠️ **Não apontar firmware real para este endpoint ainda.** Ele responde 2xx sem persistir
> (`framesStored: 0`), e o ESP32 apaga o buffer ao ver 2xx. A gravação entra no checkpoint 2.5.

### ✅ 1.4 · Primeiro teste com Testcontainers
**Aceite:** as duas metades foram verificadas. A suíte sobe um PostgreSQL 17 real com
TimescaleDB, e `create_hypertable` funciona nele (o que nenhum banco em memória faz). E
apontando a configuração para uma imagem inexistente, **o teste falha** — não existe queda
silenciosa para banco em memória.
Medido com os eventos do Docker: **um único container** para os 27 testes.
**Fechado em 10/09/2026.** Testcontainers 2.x mudou os nomes dos módulos e tirou o genérico do
`PostgreSQLContainer` — registrado na tabela de atrito do `docs/11`.

### ✅ 1.5 · Gerador de dados sintéticos
Pré-requisito, não extra (ADR-005). Curva de RPM plausível, temperatura subindo, GPS num traçado.
**Aceite:** rodou 60 s contra a API real — 6.671 frames em 12 lotes, ~111 frames/s — e reenviou
lotes com o **mesmo** `batchId`. Os frames foram capturados da rede e decodificados com
`cantools`: RPM variando ~1.000 de amplitude dentro da faixa do DBC, temperatura subindo com o
uso, marcha coerente com a velocidade, GPS num traçado fechado.
**Fechado em 10/09/2026.** A codificação (`FrameEncoder`) entrou no domínio em vez de ser
hardcodada no gerador — ela é a inversa do decodificador e o teste de propriedade da Fase 2 vai
precisar dela. Bate **byte a byte** com o `cantools` nos três frames.

> O gerador denuncia o que ainda não existe: ao reenviar um lote, ele avisa que os frames
> entraram duas vezes e a API não percebeu — esperado até o checkpoint 2.6.

---

## ✅ Fase 1 completa

A API sobe, recebe o contrato, tem banco no Compose, testa contra Postgres real, e há como gerar
telemetria sem o carro. **Nada é persistido ainda** — é o que a Fase 2 resolve.

---

## Fase 2 — Modelo de dados e decodificador

*O coração do sistema. É aqui que erro não gera exceção, gera dado silenciosamente errado.*

### ✅ 2.1 · Flyway e primeiras tabelas
**Aceite:** as duas metades verificadas contra o banco do Compose. A primeira subida aplicou
`V1__extensao_timescaledb` e `V2__tabelas_base`; a segunda respondeu
*"Schema public is up to date. No migration necessary."*
**Fechado em 10/09/2026.** Cinco testes novos, incluindo a prova de que a chave primária de
`ingest_batch` recusa o mesmo `batchId` duas vezes — a idempotência do ADR-008 é do banco, não
de checagem no código. As hypertables entram na 2.2.

### ✅ 2.2 · `raw_frame` como hypertable
**Aceite:** verificado contra o banco do Compose — dado de 22 a 24/08 gerou **três chunks**, um
por dia, criados sozinhos; e o `EXPLAIN` de uma consulta escopada a um dia abre **um só**, pelo
índice `idx_raw_session_time`.
**Fechado em 10/09/2026.** Oito testes novos, incluindo os que provam que a ausência de chave
primária e de chave estrangeira é **decisão** e não esquecimento, e que não existe política de
retenção (apagar o cru quebraria o ADR-001).

> **Achado ao implementar:** as fronteiras de chunk são alinhadas em **UTC**, não no fuso local.
> Em Brasília isso cai às 21h — uma bateria noturna que atravesse esse horário ocupa dois chunks.
> Não quebra nada, e o `docs/06 §4.3` foi corrigido.

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
