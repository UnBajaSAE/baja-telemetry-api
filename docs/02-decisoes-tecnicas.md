# 02 · Decisões técnicas

Cada decisão registrada no formato **ADR** (*Architecture Decision Record*): contexto, decisão,
consequências. O objetivo é que daqui a seis meses — ou numa entrevista — a pergunta "por que
assim?" tenha resposta escrita, não memória.

---

## ADR-001 · Guardar o frame cru **e** o sinal decodificado

**Status:** aceito

### Contexto

Chega um frame CAN. Existem três caminhos possíveis de persistência.

| Estratégia | Vantagem | Problema |
|---|---|---|
| Só cru | Fiel à origem, nunca perde informação | Toda consulta exige decodificar na hora — inviável em milhões de linhas |
| Só decodificado | Consulta rápida e direta | Se a definição do sinal estava errada, o dado está errado **para sempre** |
| Ambos | Consulta rápida **e** possibilidade de correção | Custo de armazenamento maior |

O segundo caso não é hipotético. Escala errada, offset errado, endianness invertida e sensor
recalibrado são ocorrências normais numa temporada — especialmente numa equipe de estudantes,
onde o firmware muda com frequência.

### Decisão

Persistir os dois. O frame cru numa tabela append-only, imutável. Os sinais decodificados em
outra, tratada como **derivada e reconstruível**.

### Consequências

**Positivas.** Descobrir em novembro que a escala do sensor de combustível estava errada desde
agosto deixa de ser perda de temporada: reprocessa-se o histórico a partir do cru. A tabela crua
também serve de trilha de auditoria e permite *replay* de uma sessão inteira para depurar o
decodificador.

**Negativas.** Armazenamento maior (o cru é compacto — 8 bytes de payload — então na prática é
barato). O reprocessamento precisa ser idempotente para não duplicar sinal.

**Princípio geral.** Isto é o padrão de pipeline de dados: **preservar o dado bruto imutável e
tratar o processado como derivado descartável.** Se o derivado pode ser reconstruído, um erro de
processamento é um inconveniente; se não pode, é perda permanente.

---

## ADR-002 · PostgreSQL com TimescaleDB

**Status:** aceito

### Contexto

Estimativa de volume, com ~500 mensagens/s somando os quatro nós:

| Cenário | Duração | Linhas de frame | Linhas de sinal (aprox.) |
|---|---|---|---|
| Teste curto | 20 min | ~600 mil | ~1,5 milhão |
| Prova de enduro | 4 h | ~7,2 milhões | ~18 milhões |
| Temporada | — | centenas de milhões | bilhões |

**É esse número que justifica tudo.** Numa tabela de 500 linhas, qualquer escolha funciona e
nenhuma decisão técnica importa. Em dezenas de milhões, `SELECT AVG(valor) WHERE tempo BETWEEN …`
sem particionamento varre a tabela inteira, e inserir linha a linha não acompanha a taxa de
chegada.

A dificuldade deste projeto é honesta: **vem do domínio, não de complexidade fabricada.**

### Decisão

PostgreSQL com a extensão TimescaleDB. Não é um banco diferente — é Postgres, com SQL normal,
transação normal e as mesmas ferramentas. A extensão adiciona particionamento automático por
janela de tempo (*hypertable*).

### Alternativas consideradas

**Postgres puro com particionamento nativo.** Funciona e é uma alternativa legítima; exige criar
e manter as partições manualmente. Fallback se o TimescaleDB complicar o deploy gerenciado.

**InfluxDB / banco de série temporal dedicado.** Melhor em escrita bruta, mas obriga a manter um
segundo banco só para isso, tem linguagem de consulta própria e não faz JOIN com os dados
relacionais (sessão, piloto, configuração do carro). Não compensa nesta escala.

**MongoDB.** Não resolve o problema real, que é agregação sobre janela de tempo.

### Consequências

Consulta de intervalo toca só as partições relevantes. Retenção e agregação contínua vêm de
graça. Em contrapartida, nem todo Postgres gerenciado traz a extensão — o que restringe as opções
de hospedagem e precisa ser verificado **antes** da Fase 5.

---

## ADR-003 · Testcontainers em vez de banco em memória

**Status:** aceito

### Contexto

Os testes de integração precisam de um banco. A alternativa tradicional é H2 ou HSQLDB em
memória, que sobem em milissegundos.

### Decisão

Testcontainers, subindo um container Postgres real (com TimescaleDB) durante a execução dos
testes.

### Justificativa

**H2 não é Postgres.** Ele não tem os tipos, não tem as funções, não tem a extensão TimescaleDB, e
não reproduz o comportamento de concorrência e isolamento do Postgres. Um teste que passa no H2 e
quebra em produção não é raro — é rotina. Um teste que dá falsa confiança é pior que teste
nenhum, porque desliga a desconfiança.

Aqui a diferença é decisiva: nada do que este projeto tem de específico — hypertable, batch
insert, `ON CONFLICT` para idempotência — existe no H2. A suíte testaria justamente o que não
importa.

### Consequências

Suíte mais lenta (segundos em vez de milissegundos) e Docker vira pré-requisito para rodar
teste, inclusive no CI. Em troca, o que passa no teste tem chance real de funcionar em produção.

---

## ADR-004 · Ingestão em lote, com idempotência

**Status:** aceito

### Contexto

O carro sai do alcance do WiFi. Sempre. Isso não é caso de borda — é o modo normal de operação.

O ESP32 não pode fazer um POST por frame: a 500 mensagens/s isso seria 500 requisições HTTP por
segundo, cada uma com overhead maior que o dado que carrega, e nenhuma delas entregue enquanto o
carro estiver na pista. Ele precisa acumular em buffer local (cartão SD) e despejar quando a
conexão voltar.

### Decisão

O endpoint de ingestão recebe **lotes**, cada lote identificado por um `batchId` gerado pelo
cliente. Lote já processado é reconhecido e ignorado.

### Consequências — os três problemas que isso cria

**Batch insert obrigatório.** Mil `INSERT` separados é ordem de grandeza mais lento que um comando
com mil linhas, porque cada ida e volta paga latência de rede. Requer
**`reWriteBatchedInserts=true`** na URL JDBC — sem a flag o driver não funde os comandos.

> ⚠️ **Correção (checkpoint 2.5).** Este ADR trazia `rewriteBatchedStatements=true`, que é o
> parâmetro do **MySQL**. O driver do PostgreSQL usa `reWriteBatchedInserts` e **ignora em
> silêncio** o nome errado — a ironia é que seguir o texto original produziria exatamente o
> problema que ele descreve. Medido com 5.000 linhas em `raw_frame`:
>
> | Como | Tempo | Taxa |
> |---|---|---|
> | Linha a linha | 1.854 ms | 2.696 linhas/s |
> | Em lote, sem a flag | 769 ms | 6.501 linhas/s |
> | **Em lote, com `reWriteBatchedInserts`** | **133 ms** | **37.593 linhas/s** |
> | Em lote, com o nome do MySQL | 751 ms | 6.657 linhas/s ← idêntico a "sem a flag" |
>
> Em lote com a flag é **13,9×** mais rápido que linha a linha; a flag sozinha responde por
> **5,8×** disso. O benchmark está em `BatchInsertBenchmarkTest`.

**A flag muda o retorno de `batchUpdate`.** Ao fundir os comandos, o driver perde a contagem por
linha e devolve `SUCCESS_NO_INFO` (−2) para cada uma. Somar direto produz número negativo — foi o
que aconteceu aqui, e a API chegou a responder `framesStored: -4` para 2 frames. O teste não pegou
porque o container do Testcontainers subia **sem** a flag: banco real, driver configurado
diferente. Hoje o container usa a mesma URL da produção.

**Idempotência.** A conexão cai no meio do envio; o ESP32 não sabe se o servidor recebeu e
reenvia. Sem proteção, duplica-se meia sessão. Resolvido com `batchId` único e `ON CONFLICT DO
NOTHING`.

**Timestamps fora de ordem.** O buffer de dez minutos atrás chega *depois* do dado ao vivo. O
modelo de dados precisa aguentar inserção fora de ordem cronológica — o que elimina otimizações
que assumem append monotônico, e é exatamente um dos motivos de existir o TimescaleDB.

> Detalhe de campo: o relógio do ESP32 tem deriva e pode reiniciar. O timestamp precisa vir do
> dispositivo (é o único que sabe *quando* o frame foi lido), mas a API deve registrar também seu
> próprio horário de recebimento, para permitir correção posterior de deriva.

---

## ADR-005 · Gerador de dados sintéticos desde a Fase 1

**Status:** aceito

### Contexto

O carro não fica disponível para desenvolvimento. Não está no laboratório de madrugada, não está
numa entrevista de emprego, e não está rodando quando o CI executa.

### Decisão

Construir, junto com o primeiro endpoint, um gerador que se passa pelo ESP32 e produz frames CAN
realistas — curva de RPM plausível, temperatura subindo com o uso, GPS percorrendo um traçado,
e a capacidade de simular queda de conexão e reenvio de lote.

### Consequências

Desenvolvimento e teste de carga deixam de depender de hardware. A demonstração funciona em
qualquer lugar. E o gerador vira ferramenta de teste da própria equipe de eletrônica.

**Esta é a decisão mais subestimada do projeto.** Sem ela, o desenvolvimento fica refém da agenda
do carro — e projeto que só roda com hardware presente não é demonstrável.

---

## ADR-006 · O DBC como fonte única de verdade

**Status:** aceito

### Contexto

O decodificador precisa saber, para cada frame, quais sinais moram nele e com que bit inicial,
largura, endianness, escala, offset e faixa. Hoje essa informação existe — mas espalhada pelo
firmware do ESP32, em `#define` de máscara e `struct` de campo de bit.

Isso já é um problema **antes** desta API existir: se alguém muda a escala do sensor de
combustível no firmware e não avisa, o display mente e ninguém descobre. Com a API no meio, o
problema piora — o dado errado passa a ser **persistido**, e um gráfico errado é mais
convincente que um display errado.

O formato precisa ser decidido antes da primeira linha do decodificador, porque ele é a entrada
de tudo.

### Decisão

Um arquivo **DBC** (formato Vector, padrão de fato da indústria automotiva) em
`contracts/can/unbaja.dbc`, lido por um **parser próprio** que cobre o subconjunto `BO_` + `SG_`.

### Alternativas consideradas

**YAML ou JSON próprio.** Zero parser para escrever — o Jackson lê direto. Descartada porque
joga fora o principal ganho: um formato próprio só este repositório entende. O DBC abre em
SavvyCAN, CANalyzer, BusMaster e `cantools`, o que significa que a mesma definição serve de
segunda opinião independente na hora de depurar o barramento na bancada, e que o dia em que a
equipe adotar qualquer ferramenta de CAN comercial, o arquivo já está pronto.

**Biblioteca de parsing DBC na JVM.** Evitaria escrever código. Descartada por duas razões: as
opções maduras no ecossistema JVM são escassas e pouco mantidas comparadas ao `cantools` do
Python, e o subconjunto de que precisamos são duas diretivas — trocar ~80 linhas de código
testável por uma dependência opaca é mau negócio quando o código em questão é justamente o
coração do sistema.

**Gerar código Kotlin a partir do DBC em tempo de build.** Mais rápido em runtime, e elimina o
parsing do caminho quente. Descartada por antecipar otimização: o DBC é lido **uma vez** na
subida da aplicação, não por frame. Reconsiderar só se a Fase 5 medir isso como gargalo, o que
é improvável.

### Consequências

**O parser precisa falhar alto.** Ao encontrar uma diretiva não suportada — `VAL_`,
multiplexação, `BA_` — ele deve levantar erro e recusar a subida da aplicação, **nunca** pular a
linha em silêncio. Ignorar um `SG_` desconhecido significa perder um sinal inteiro sem que
ninguém note, que é o modo de falha mais caro deste projeto.

**Ganho que vale por si só, independente da API.** Consolidar o mapa num arquivo versionado
resolve um problema que a equipe de eletrônica já tem hoje. Mesmo que este backend fosse
cancelado, o DBC continuaria útil.

**O arquivo atual é fictício.** Foi escrito para destravar o desenvolvimento antes do
levantamento do firmware. Trocar o conteúdo depois não gera retrabalho, porque o DBC é dado de
entrada e não código — mas exige reprocessar o histórico a partir de `raw_frame`, que é
justamente a capacidade que o ADR-001 comprou.

**Custo aceito.** Uma parte do formato DBC fica sem suporte, e cada nova diretiva necessária
vira trabalho de parser. O limite atual está registrado em
[`docs/05-mapa-de-sinais.md §5.2`](05-mapa-de-sinais.md).

---

## ADR-007 · O `sessionId` é gerado pelo dispositivo

**Status:** aceito

### Contexto

Toda consulta deste sistema é escopada por sessão — "a bateria de suspensão de terça", "a prova
de enduro". A sessão precisa de identificador, e alguém precisa criá-lo.

O detalhe que decide: **o carro coleta sem rede.** Ele sai do alcance do WiFi e volta; isso não é
caso de borda, é o modo normal de operação (ADR-004).

### Decisão

O dispositivo gera o `sessionId`, no formato `AAAA-MM-DD-descricao-em-slug`
(ex.: `2026-08-24-teste-suspensao`), e o envia em todo lote. A API faz *upsert* — cria a sessão
na primeira vez que a vê, com `ON CONFLICT DO NOTHING`.

### Alternativas consideradas

**A API cria, via `POST /sessions`.** É o desenho convencional, e garante unicidade porque só um
lado gera. Descartada por um motivo prático e decisivo: **exigiria rede antes de começar a
coletar.** O piloto entra na pista, aperta o botão, e o ESP32 não teria id para carimbar nos
frames que já está lendo. Otimizar para o caso com rede quando o caso sem rede é o normal
inverte a prioridade.

**UUID gerado pelo dispositivo.** Resolve a unicidade de vez e continua funcionando offline.
Descartada porque `550e8400-e29b-41d4-a716-446655440000` não diz nada: a lista de sessões vira
uma parede de hexadecimal, e comparar "o teste de hoje com o da semana passada" — que é o caso de
uso que motiva o projeto — exige consultar outra tabela para descobrir qual é qual.

### Consequências

**Vários dispositivos compartilham a mesma sessão, e isso é desejado.** Os nós carimbam o mesmo
`sessionId` e a telemetria dos quatro se junta naturalmente. O `deviceId` distingue a origem
dentro da sessão.

**Metadados divergentes: o primeiro ganha.** Se dois dispositivos mandarem descrições diferentes
para o mesmo id, o `ON CONFLICT DO NOTHING` mantém a primeira. Alternativa seria falhar, o que
significaria rejeitar dado bom por causa de um campo cosmético.

**Erro de digitação cria sessão fantasma.** `2026-08-24-teste-suspenao` vira uma sessão nova, com
uma fatia dos dados. Risco aceito: um endpoint de renomear/fundir sessão resolve, e entra na
Fase 3 se acontecer na prática. Impedir isso exigiria validação contra uma lista prévia — ou seja,
rede antes de coletar, que é justamente o que a decisão evita.

**Requisito para o firmware:** o `sessionId` precisa sobreviver a um reinício do ESP32 no meio da
coleta. Gravar junto com o buffer no cartão SD, não só em memória.

---

## ADR-008 · Idempotência na granularidade do lote

**Status:** aceito

### Contexto

O ESP32 reenvia quando não sabe se foi entregue (ADR-004). Sem proteção, meia sessão duplica —
e dado duplicado não gera erro: gera média errada, contagem errada, e um gráfico com o dobro dos
pontos que parece só "mais denso".

A pergunta é **em que granularidade** detectar a repetição.

### Decisão

No **lote**. O `batchId` gerado pelo dispositivo é `PRIMARY KEY` da tabela `ingest_batch`; lote
com id já existente é reconhecido e ignorado, e a API responde 200 com `duplicate: true`.

A garantia mora no banco, não numa verificação no código — duas requisições simultâneas com o
mesmo `batchId` driblariam um `if (jaExiste)`, mas não driblam uma chave primária.

### Alternativa considerada

**Restrição de unicidade no frame**, sobre `(session_id, device_id, frame_time, can_id, payload)`.
Cobriria um caso que o `batchId` não cobre: o dispositivo reinicia, remonta o buffer com um
recorte diferente, e os mesmos frames chegam distribuídos em lotes de ids novos.

Descartada por três custos concretos:

1. Um índice único sobre cinco colunas em centenas de milhões de linhas ocupa muito espaço e
   **paga verificação a cada linha inserida** — 5.000 verificações por lote, no único ponto do
   sistema onde a taxa de escrita importa.
2. Gera **falso positivo**: um sinal estável (temperatura parada, marcha engatada) produz
   legitimamente frames idênticos no mesmo milissegundo. Descartá-los como duplicata perde dado
   real.
3. A hypertable obrigaria a incluir a coluna de particionamento no índice (ver
   [`docs/06 §4.1`](06-modelo-de-dados.md)), o que engorda ainda mais.

### Consequências

**Existe um cenário não coberto, e ele está nomeado.** Reinício com rebufferização diferente
duplica frames. A mitigação é do lado do firmware, e é barata: **gravar o `batchId` no cartão SD
junto com o buffer**, e não apenas em memória. Assim o mesmo recorte volta com o mesmo id depois
do reinício, e a proteção de lote basta.

**É trabalho de firmware, não de backend** — e precisa entrar no
[`docs/03-protocolo-ingestao.md`](03-protocolo-ingestao.md) como requisito explícito, porque é o
tipo de detalhe que ninguém deduz lendo só a API.

**Reavaliar com dado medido.** Se a Fase 5 mostrar duplicatas reais no histórico, a restrição por
frame volta à mesa — aí com número em mãos, não por antecipação.

---

## ADR-009 · Domínio isolado do framework

**Status:** aceito

### Contexto

A estrutura de pacotes precisa ser escolhida antes da primeira classe, senão ela se forma por
sedimento — cada arquivo novo indo onde deu, até ninguém saber mais onde procurar.

O fator que decide aqui é específico deste projeto: **o decodificador de frame será testado com
teste de propriedade**, milhares de casos aleatórios por execução. Isso impõe um requisito à
estrutura, não só ao teste.

### Decisão

Quatro camadas, com uma regra dura: **o pacote `domain` não importa nada de Spring** — nem
`@Component`, nem Jackson, nem JPA.

```
domain/       decodificador, parser DBC, modelo de sinal   ← Kotlin puro
application/  orquestração                                 ← @Service, @Transactional
adapter/      web (controllers, DTOs) e persistence (JDBC)
config/
```

A regra é verificada por **teste de arquitetura** (ArchUnit): se alguém anotar uma classe de
`domain`, o build quebra.

### Alternativas consideradas

**Package-by-layer** (`controller/`, `service/`, `repository/`). É o que todo tutorial de Spring
mostra e qualquer dev Java reconhece de imediato — vantagem real, que não deve ser subestimada.
Descartada porque colocaria o `FrameDecoder` dentro de `service/`, junto de classes anotadas.
O teste de propriedade passaria a exigir contexto do Spring: segundos de bootstrap por execução,
em vez de milissegundos. **Teste lento não fica lento — fica não executado.**

**Hexagonal completa (ports & adapters).** Academicamente a mais correta. Descartada porque a
cerimônia não se paga em cinco endpoints: cada operação passaria por três arquivos, e a resposta
honesta a "por que essa indireção existe?" seria "porque o padrão manda" — a pior resposta
possível numa entrevista.

Manteve-se dela **só a parte que se paga**: a inversão entre `application` e `persistence`, que
existe para testar o serviço sem banco. Cada indireção deste projeto tem um teste que a justifica.

### Consequências

**O decodificador fica testável em milissegundos**, sem framework em volta — que era o objetivo.

**Uma camada a mais que o óbvio**, e um `IngestRequestDto` separado do `IngestBatch` de domínio.
Conversão a mais para escrever; em troca, mudança no formato do JSON deixa de encostar no
decodificador.

**A regra precisa de fiscal.** Sem o teste de arquitetura ela dura até a primeira pressa. Custa
uma dependência de teste e um arquivo — ver [`docs/07 §6`](07-arquitetura-do-codigo.md).

---

## ADR-010 · Aceitação parcial de lote

**Status:** aceito

### Contexto

O cartão SD teve um bit invertido, ou o firmware montou um frame torto. Num lote de 1.000, **um**
frame chega inválido — hex com número ímpar de dígitos, payload acima de 8 bytes, `canId` fora da
faixa.

O corpo é perfeitamente legível. Só um item dentro dele está corrompido.

### Decisão

**Aceitar os frames válidos e reportar os rejeitados**, com 200. A resposta traz
`framesStored`, `framesRejected` e uma lista com índice e motivo de cada rejeição.

Vale apenas para frames individuais dentro de um corpo legível. JSON que não parseia, ou lote sem
`batchId`/`sessionId`, continua sendo 400 com o lote inteiro rejeitado — não há o que salvar.

### Alternativa considerada

**Tudo ou nada: rejeitar o lote inteiro com 400.** É mais simples de implementar e de raciocinar,
e mantém a promessa de que um lote aceito está integralmente no servidor.

Descartada porque cria um beco sem saída: **o firmware não tem como consertar aquele byte.** O
frame já está gravado torto no cartão. As duas saídas seriam perder os 999 frames bons junto, ou
retentar para sempre um lote que nunca vai passar — e retentar para sempre significa o buffer
encher e a coleta parar. Um bit invertido derrubaria a telemetria do dia.

### Consequências

**O frame rejeitado é perdido, e isso é aceito.** O firmware apaga o buffer ao ver 2xx. O frame
era ilegível de qualquer forma — mas a perda precisa ser **auditável**, não silenciosa.

**Exige uma coluna nova:** `ingest_batch.rejected_count`, mais o conteúdo dos frames rejeitados no
log. Sem isso, corrupção de cartão SD viraria perda gradual e invisível de dado.

**Rejeição vira sinal de diagnóstico.** Se `rejected_count` começar a subir numa sessão, o
problema é físico — cartão ruim, alimentação instável, ruído no barramento. O gráfico de
rejeições por sessão é a ferramenta que denuncia isso, e é útil para a equipe de eletrônica
independente do backend.

**A resposta de sucesso fica mais rica** — o firmware precisa comparar `framesReceived` com
`framesStored` em vez de só olhar o status. Custo pequeno, e o
[`docs/08 §2.1`](08-contrato-de-erros.md) mantém a decisão do firmware simples.

---

## ADR-011 · Agregados de sessão guardados por lote

**Status:** aceito

### Contexto

O `GET /sessions` precisa devolver, para cada sessão, **quando começou, quando terminou e quantos
frames tem**. A informação existe — está espalhada em milhões de linhas de `raw_frame` — mas
juntá-la a cada consulta é caro.

O rascunho da Fase 0 tinha colunas `started_at` e `ended_at` na tabela `session`, declaradas como
"derivadas". Nada nunca as preencheu: quando a persistência chegou no checkpoint 2.5, elas
continuaram nulas. A decisão de **como** derivá-las ficou em aberto até aqui.

O fato que decide é uma medição, com 2,2 milhões de frames em três sessões:

| Origem do agregado | Linhas tocadas | Tempo |
|---|---|---|
| `raw_frame` | 2.200.000 | **220 ms** |
| `ingest_batch` | 36 | **1,5 ms** |

A meta do [`docs/10`](10-requisitos-nao-funcionais.md) para essa consulta é **≤ 100 ms**. Varrer o
cru já a estoura com menos de uma prova de enduro dentro — e a `raw_frame` cresce com a temporada,
enquanto a `ingest_batch` cresce com o número de lotes.

### Decisão

Cada lote grava o **próprio** menor e maior `frame_time`, em duas colunas novas de `ingest_batch`.
O `GET /sessions` agrega sobre essa tabela.

As colunas `session.started_at` e `session.ended_at` foram **removidas** na migration V5.

### Alternativas consideradas

**Manter `started_at`/`ended_at` na linha da sessão, com `UPDATE` a cada lote.** A consulta ficaria
trivial — ler uma linha pronta, sem agregação. É a opção mais óbvia, e tem um argumento real a
favor: nenhum custo de leitura.

Descartada por causa de um resultado do checkpoint anterior. O 2.6 mostrou que **oito requisições
simultâneas para a mesma sessão serializam no `upsert` da linha da sessão** — a primeira segura a
linha até commitar e as outras ficam na fila. Acrescentar um `UPDATE` na mesma linha a cada lote
transformaria esse acidente em desenho: com quatro nós enviando ao mesmo tempo, eles entrariam em
fila no ponto do sistema onde a taxa de escrita importa. A alternativa escolhida não tem
contenção nenhuma, porque cada requisição escreve **a sua própria** linha — que já estava sendo
inserida de qualquer forma.

**Agregado contínuo do TimescaleDB.** Uma view materializada mantida pelo banco, sem mudar código
de escrita. Descartada aqui por dois motivos: ela é a ferramenta certa para agregar por **janela
de tempo** (o caso do checkpoint 3.3, onde levou a consulta de 6.899 ms para 3,8 ms), e não para
agrupar por sessão; e o *refresh* dela lê a `raw_frame`, ou seja, paga o custo que se está
tentando evitar — só que em segundo plano.

### Consequências

**Medido depois de implementar:** o endpoint responde em **7 ms** com 2,16 milhões de frames no
banco, porque a consulta toca 39 linhas em vez de 2,16 milhões.

**Duas colunas podem ficar nulas.** Um lote em que nenhum frame foi aceito não tem mínimo nem
máximo. A listagem trata isso: a sessão aparece com duração nula em vez de sumir.

**A migration precisou de *backfill*.** Os lotes que já existiam não tinham as colunas. O
`UPDATE` que as preenche a partir de `raw_frame` roda uma vez, e levou 1,4 s para 2,1 milhões de
linhas — aceitável, mas num banco de produção grande vale rodar fora do horário de coleta.

**Colunas mortas foram removidas, não deixadas.** `session.started_at` sempre nula, com esse nome,
seria armadilha para a próxima pessoa. O `docs/06` foi corrigido junto.

**O número passa a vir de uma soma, não de um campo pronto.** Se um dia a listagem precisar de
algo que a `ingest_batch` não sabe responder — por exemplo, contagem por sinal — essa decisão terá
que ser revisitada. Para duração e contagem de frames, ela basta.

---

## ADR-012 · Agregado contínuo de 1 segundo

**Status:** aceito

### Contexto

O `GET /sessions/{id}/summary` precisa do mínimo, máximo e média de cada sinal na sessão inteira.
A `signal_point` é a maior tabela do sistema — uma prova de enduro de 4 h tem 18 milhões de
pontos.

Medido, com os índices já existentes:

| Origem | Tempo |
|---|---|
| Agregando `signal_point` direto | **3.658 ms** |
| Sobre o agregado contínuo | **29,7 ms** |

A meta do [`docs/10`](10-requisitos-nao-funcionais.md) é 200 ms. A consulta direta fica **18×**
acima — e nem é o pior caso: a série por janela de 1 s da mesma sessão custava 6.899 ms.

O [`docs/06 §6`](06-modelo-de-dados.md) já tinha registrado, na Fase 0, que a decisão seria
tomada "com o número em mãos". O número apareceu.

### Decisão

Um agregado contínuo `signal_1s`, agrupando por sessão, sinal e janela de 1 segundo.

Ele guarda **soma e contagem**, não média. E é criado com **agregação em tempo real ligada**.

### Duas escolhas dentro da decisão

**Soma e contagem, nunca `avg()`.** Guardar a média de cada janela obrigaria quem consulta a
tirar média das médias — o que só dá certo se todas as janelas tiverem o mesmo número de pontos.
Com 2 leituras a 800 rpm numa janela e 198 a 4.000 na seguinte, a média das médias dá **2.400 rpm**
e a ponderada dá **3.968**. Na nossa telemetria de taxa constante as janelas são quase uniformes e
o erro seria pequeno — mas gaps e bordas de sessão existem, e guardar as duas colunas custa nada.

**`materialized_only = false`, explicitamente.** Nas versões recentes do TimescaleDB o padrão
virou `true`: a consulta só enxerga o que a política já materializou. Isso faria quem termina uma
bateria e pede o resumo na hora **não ver nada** por até um minuto — o "mandei e não apareceu" que
o [`docs/07 §5`](07-arquitetura-do-codigo.md) citou ao recusar decodificação assíncrona. Foi
descoberto porque havia um teste específico para isso.

**A janela da política é de 8 dias, não 1.** O [`docs/03`](03-protocolo-ingestao.md) aceita frames
com até 7 dias de idade, e o [ADR-004](02-decisoes-tecnicas.md) diz que dado chega **fora de
ordem**: um buffer de ontem aparece hoje, com carimbo de ontem. Uma janela de 1 dia deixaria esse
dado sem materializar para sempre. Varrer 8 dias não custa, porque o TimescaleDB registra quais
janelas foram invalidadas por inserção e só reprocessa essas.

### Alternativas consideradas

**Índice melhor sobre `signal_point`.** Foi o primeiro reflexo, e a Fase 0 já tinha medido que não
resolve: o índice composto rende 1,4× na agregação de sessão inteira, porque o TimescaleDB já cria
um índice de tempo sozinho. O problema não é encontrar as linhas — é que são 18 milhões delas.

**Manter os agregados por lote**, como o [ADR-011](02-decisoes-tecnicas.md) fez para a listagem.
Funcionaria para contagem e faixa, mas não para série temporal: o checkpoint 3.3 precisa de
mínimo, máximo e média **por janela de tempo**, e um lote não se alinha a janela nenhuma. Seria
resolver metade do problema e ter que fazer o agregado contínuo do mesmo jeito.

### Consequências

**Medido depois de implementar:** o endpoint responde em **~70 ms** com 18 milhões de pontos.

**A criação é cara e não roda em transação.** Materializar 18 milhões de pontos levou 84 s, e o
Postgres recusa `CREATE MATERIALIZED VIEW ... WITH DATA` dentro de transação — a migration tem um
`.sql.conf` com `executeInTransaction=false`. Em troca, ela **não tem rollback automático**: se
falhar no meio, a view pode ficar sem a política e a correção é manual.

**A consulta paga o rabo recente.** Com agregação em tempo real, o que ainda não foi materializado
é lido da `signal_point` ao vivo. O rabo é de minutos, então o custo é pequeno — mas num pico de
ingestão ele cresce, e vale remedir na Fase 5.

**O agregado é armazenamento a mais.** Medido na Fase 0: 14 MB contra 3.145 MB da tabela bruta,
**225× menor**, porque 4 h viram 14.400 janelas por sinal em vez de 18 milhões de pontos.

**Ele é descartável.** Como a `signal_point`, o agregado é derivado e pode ser reconstruído — o
que preserva a propriedade do [ADR-001](02-decisoes-tecnicas.md).
