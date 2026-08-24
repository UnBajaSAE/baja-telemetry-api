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
`rewriteBatchedStatements=true` no JDBC — sem essa flag o driver ignora o batch silenciosamente.

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
