---
name: kotlin-spring
description: Convenções de código deste repo — estrutura de pacotes, idioma dos identificadores, injeção de dependência, data class vs value class, e o padrão de teste com Testcontainers. Use SEMPRE que for escrever, mover ou revisar código Kotlin/Spring neste projeto, e ao decidir onde um arquivo novo deve morar.
---

# Convenções de código — baja-telemetry-api

Decisões pequenas demais para virarem ADR, mas que precisam ser consistentes. As grandes estão em
[`docs/07`](../../../docs/07-arquitetura-do-codigo.md) e no ADR-009.

## 1. A regra dura

> **O pacote `domain` não importa nada de Spring** — nem `@Component`, nem Jackson, nem JPA.

Existe um teste ArchUnit que **quebra o build** se isso for violado. Não desative o teste: se um
arquivo de `domain` parece precisar de Spring, ele está no pacote errado.

Motivo (ADR-009): o decodificador é testado com propriedade, milhares de casos por execução. Com
Spring no caminho, cada execução paga bootstrap de contexto — e **teste lento não fica lento, fica
não executado.**

## 2. Onde o arquivo novo mora

```
domain/        lógica pura: decodificador, parser DBC, modelo de sinal   ← sem framework
application/   orquestração: @Service, @Transactional, interfaces de port
adapter/web/   controllers, DTOs, tratamento de erro
adapter/persistence/  repositórios JDBC
config/        beans, carga do DBC na subida
```

A pergunta que decide: **"isso precisa de framework para funcionar?"** Se não precisa, é `domain`.

## 3. Idioma

**Identificadores em inglês**, consistente com o que já existe (`raw_frame`, `signal_point`,
`FrameDecoder`, `IngestService`). **Comentários e mensagens de commit em português**, sem acento em
nome de arquivo ou branch.

Comentário explica **por quê**, nunca o quê. `// incrementa o contador` é ruído; `// sem esta flag
o driver ignora o batch em silencio` é o que salva a próxima pessoa.

## 4. Kotlin

**`data class` para o que carrega dado** (DTOs, resultados). **`value class` para identificador que
não pode ser trocado por engano** — `SessionId`, `CanId`, `BatchId`. O compilador passa a recusar
`fun buscar(sessionId: String)` recebendo um `deviceId`, e isso não custa objeto em runtime.

**Nada de `!!`.** Se o tipo é anulável e você tem certeza que não é nulo, ou o tipo está errado ou
falta uma validação na fronteira. `!!` é `NullPointerException` adiada.

**Fronteiras validam, o miolo confia.** Depois que o controller converteu o DTO em `IngestBatch`,
o domínio assume que o dado é válido. Revalidar em toda camada espalha a regra e ninguém sabe mais
onde ela mora.

## 5. Spring

**Injeção por construtor, sempre.** Nada de `@Autowired` em campo — construtor deixa a dependência
explícita e permite instanciar a classe num teste sem framework:

```kotlin
@Service
class IngestService(
    private val rawFrames: RawFrameStore,
    private val decoder: FrameDecoder,
)
```

**`@Transactional` na camada `application`**, nunca no controller nem no repositório. É o
`IngestService` que sabe onde a transação começa e termina.

**Schema só por Flyway.** Nunca `ddl-auto`. Migration é `V{n}__descricao.sql`, numerada, e **nunca
se edita uma migration já aplicada** — cria-se a próxima.

## 6. Testes

**Um container para a suíte inteira**, não um por classe. Classe base com o container em
`companion object` + `@ServiceConnection`. Vinte classes com container próprio são vinte
bootstraps.

**O schema do teste vem do Flyway**, o mesmo que vai para produção — inclusive o
`create_hypertable`. Testar contra schema gerado pelo framework é testar um banco que não existe.

**Teste junto com o código, nunca depois.** Checkpoint sem teste não fecha — ver
[`docs/09`](../../../docs/09-estrategia-de-testes.md).

## 7. Armadilhas já conhecidas deste projeto

**`reWriteBatchedInserts=true` na URL JDBC** — e repare no nome. `rewriteBatchedStatements` é o
parâmetro do **MySQL**, e o driver do Postgres o ignora em silêncio: sem erro, só lento.
Medido: **13,9×** de diferença (ADR-004).

**A flag muda o retorno de `batchUpdate`.** Com ela ligada o driver devolve `SUCCESS_NO_INFO`
(−2) por linha em vez da contagem. Somar direto dá número negativo. Use o helper
`linhasGravadas()` em `JdbcStores.kt`.

**O container de teste precisa da MESMA URL da produção.** Banco real com driver configurado
diferente ainda é falsa confiança — foi assim que o `framesStored: -4` passou pela suíte.

**Hypertable não aceita chave primária que não inclua a coluna de tempo.** `raw_frame` e
`signal_point` não têm PK, de propósito ([`docs/06 §4.1`](../../../docs/06-modelo-de-dados.md)).

**`offset` é palavra reservada no SQL.** A coluna chama `offset_value`.

**Nunca responder 2xx antes do commit.** O ESP32 apaga o cartão ao ver 2xx — um 2xx antes da hora
destrói dado de forma irrecuperável.
