# 04 · Glossário

Jargão que aparece no projeto, dos dois lados: o domínio automotivo e a stack de backend.

---

## Domínio — barramento e telemetria

**CAN (Controller Area Network).** Barramento onde as ECUs do carro conversam. Não tem
servidor nem endereço de destino: toda mensagem vai para todos, e cada nó decide se lhe
interessa pelo identificador.

**ECU (Electronic Control Unit).** Cada módulo eletrônico do carro. O Baja tem quatro nós.

**Frame.** A unidade transmitida no CAN: identificador de 11 bits e até 8 bytes de payload.
É o que a API recebe.

**DLC (Data Length Code).** Quantos bytes de dado o frame carrega, de 0 a 8.

**Payload.** Os bytes de dado do frame. Opacos — não têm significado sem a tabela de sinais.

**Sinal.** Uma grandeza física individual (RPM, temperatura). **Vários sinais cabem num mesmo
frame** — esta é a distinção mais importante do projeto: o que chega pela rede é frame, o que
se consulta é sinal.

**Escala e offset.** Os dois números que convertem byte cru em grandeza física, pela fórmula
`físico = (cru × escala) + offset`. Existem para espremer precisão útil em poucos bits.

**Endianness.** Ordem dos bytes num número de múltiplos bytes. *Big endian* (Motorola) põe o
byte mais significativo primeiro; *little endian* (Intel), o contrário. Errar não gera exceção
— gera dado silenciosamente errado.

**DBC.** Formato padrão da indústria automotiva que descreve o barramento: quais frames
existem, quais sinais moram em cada um, e com que bit inicial, largura, escala, offset e faixa.
Criado pela Vector, virou padrão de fato. **É a fonte única de verdade do decodificador.**

**Sessão.** Um período contínuo de coleta — um teste de suspensão, uma bateria de aceleração,
uma prova. Unidade natural de consulta e comparação.

---

## Stack — backend e dados

**JVM (Java Virtual Machine).** Máquina virtual onde Kotlin e Java rodam. Escolher Kotlin dá
acesso a todo o ecossistema Java.

**Spring Boot.** Framework backend padrão da JVM. Cuida de injeção de dependência, servidor
HTTP, acesso a banco e configuração.

**Injeção de dependência.** O framework constrói e entrega os objetos de que uma classe
precisa, em vez de a classe criá-los. Torna o código testável, porque em teste você entrega
uma versão falsa.

**Série temporal.** Dado indexado primariamente por tempo, escrito em append e consultado por
intervalo. Padrão de acesso bem diferente de CRUD, e é por isso que exige banco preparado.

**TimescaleDB.** Extensão do PostgreSQL para série temporal. **Não é outro banco** — é Postgres,
com SQL normal, mais particionamento automático por tempo.

**Hypertable.** A tabela do TimescaleDB que, por baixo, é fatiada automaticamente em partições
por janela de tempo. Consulta de intervalo toca só as fatias relevantes.

**Chunk.** Cada uma dessas fatias — a "gaveta" onde as linhas de uma janela de tempo ficam
guardadas. Aqui a janela é de **1 dia**, então uma sessão de teste cabe numa gaveta só. O chunk é
escolhido **por linha, no servidor, na hora do INSERT**, olhando o carimbo de tempo do dado — o
ESP32 nunca ouve falar nisso.

**`time_bucket()`.** A função do TimescaleDB que corta o tempo em fatias de tamanho fixo (1 s,
1 min) para agregar. `time_bucket('1 second', ts)` transforma milhares de leituras num valor por
segundo — que é o que um gráfico consegue mostrar.

**Agregado contínuo** (*continuous aggregate*). Uma tabela que guarda o **resultado já calculado**
de uma agregação, em vez de recalcular a cada consulta.

O problema que ele resolve: "média de RPM por segundo na prova inteira" obriga o banco a ler
3 milhões de leituras e agrupá-las em 14.400 janelas de 1 s — **7 segundos de trabalho, repetidos
a cada vez que alguém abre o gráfico**. Mas a prova já acabou: aquelas médias nunca mais mudam.
O agregado calcula uma vez, guarda os 14.400 números, e as próximas consultas leem o resultado
pronto — **3,8 ms** (medido, [`docs/10 §2`](10-requisitos-nao-funcionais.md)).

É o quadro branco do box: ninguém reassiste 4 h de telemetria para saber a volta mais rápida —
alguém calculou uma vez e escreveu lá.

O **contínuo** é porque ele se atualiza sozinho: dado novo faz o TimescaleDB recalcular só as
janelas afetadas, não tudo. **O que se perde é resolução** — no agregado de 1 s não dá para ver as
leituras individuais. Por isso a tabela bruta continua existindo: o agregado é atalho, não
substituto.

**Compressão (TimescaleDB).** Reorganiza fisicamente as linhas de um chunk agrupando valores que
se repetem, o que encolhe muito dado de série temporal. Medido aqui: **40×** no `raw_frame`
(928 MB → 23 MB), em dado sintético. O dado continua consultável depois de comprimido.

**Política de retenção.** Regra que apaga automaticamente dado mais velho que X. Neste projeto ela
é **assimétrica**: `signal_point` pode ser apagado (é reconstruível), `raw_frame` **nunca** — é a
fonte da verdade ([ADR-001](02-decisoes-tecnicas.md)).

**Índice BRIN (Block Range Index).** Índice que guarda o valor mínimo e máximo por bloco de
disco, em vez de apontar linha a linha. Minúsculo, e eficiente quando o dado está fisicamente
ordenado pela coluna indexada — exatamente o caso de telemetria por timestamp.

**Batch insert.** Inserir muitas linhas num comando só, em vez de um comando por linha.
Elimina a latência de ida e volta repetida. No driver do PostgreSQL exige
**`reWriteBatchedInserts=true`** na URL — atenção ao nome, porque
`rewriteBatchedStatements` é do **MySQL** e é ignorado em silêncio. Medido neste projeto:
**13,9× mais rápido** que linha a linha (ver ADR-004).

**Idempotência.** Propriedade de uma operação que, repetida, produz o mesmo resultado da
primeira vez. Essencial aqui porque o ESP32 reenvia lote quando não sabe se foi entregue.

**Flyway.** Versionamento de schema: cada mudança é um `.sql` numerado, versionado em git,
aplicado em ordem. O oposto de deixar o framework alterar tabela sozinho (`ddl-auto`).

**Testcontainers.** Biblioteca que sobe containers Docker de verdade durante os testes — aqui,
um Postgres real. Alternativa a banco em memória, que mente sobre o comportamento do banco de
produção.

**Teste de propriedade.** Em vez de verificar exemplos escolhidos à mão, verifica uma
propriedade que deve valer para qualquer entrada, gerando centenas de casos aleatórios. Para o
decodificador: `decode(encode(x)) ≈ x`.

**ADR (Architecture Decision Record).** Registro curto de uma decisão de arquitetura no formato
contexto → decisão → consequências. Ver `docs/02`.

**Problem Details (RFC 7807).** Formato padronizado de resposta de erro em HTTP, com `type`,
`title`, `status` e `detail`. Evita cada endpoint inventar seu próprio JSON de erro.

**Throughput.** Quantidade de trabalho por unidade de tempo — aqui, frames ingeridos por segundo.
Neste projeto o que dimensiona **não é o regime permanente** (500 frames/s que o carro gera), e sim
a **rajada**: o carro não manda nada enquanto está na pista, e despeja horas de buffer de uma vez
ao voltar ao box. Ver [`docs/10 §1.1`](10-requisitos-nao-funcionais.md).

**Latência p95 / p99.** O tempo abaixo do qual caem 95% (ou 99%) das requisições. Usa-se em vez da
média porque a média esconde os casos ruins: se 1 requisição em 20 demora 8 segundos, a média mal
se mexe, mas a experiência de quem pegou aquela é péssima.

**`EXPLAIN`.** Comando do Postgres que mostra **o plano** que ele pretende usar para responder uma
consulta — quais índices, quais chunks, em que ordem. É como se descobre por que uma consulta está
lenta, em vez de adivinhar. Foi um `EXPLAIN` que revelou que o índice composto rendia pouco porque
o TimescaleDB já cria um índice de tempo sozinho.

**Paginação por cursor.** Paginar usando um ponteiro para o último item visto, em vez de
`OFFSET`. `OFFSET 1000000` obriga o banco a contar e descartar um milhão de linhas antes de
devolver a página; cursor sobre coluna indexada vai direto.
