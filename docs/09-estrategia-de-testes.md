# 09 · Estratégia de testes

**"Testes automatizados" é um dos gaps que este projeto existe para fechar.** Por isso a
estratégia merece documento, não parágrafo — e por isso a regra do repositório é dura:
**checkpoint sem teste não fecha.**

---

## 1. O que estamos protegendo

Este sistema tem um modo de falha que quase nenhum CRUD tem: **erro no decodificador não gera
exceção. Gera um número plausível e errado.**

Endianness trocada, off-by-one no bit inicial, `signed` declarado como `unsigned` — nenhum deles
levanta erro. Todos produzem um valor que parece perfeitamente razoável, é gravado, aparece no
gráfico, e alguém toma decisão de engenharia em cima dele.

Um teste que dá falsa confiança é pior que teste nenhum, porque desliga a desconfiança. É esse
raciocínio que organiza tudo abaixo.

---

## 2. Os cinco níveis

| Nível | Cobre | Ferramenta | Custo por execução |
|---|---|---|---|
| **Propriedade** | O decodificador: `encode(decode(x)) == x` | kotest-property | milissegundos |
| **Unitário** | Parser DBC, validação, montagem de lote | JUnit 5 | milissegundos |
| **Arquitetura** | A regra "`domain` não importa Spring" | ArchUnit | milissegundos |
| **Integração** | Repositórios, batch insert, `ON CONFLICT`, hypertable | Testcontainers | segundos |
| **Contrato** | `POST /ingest` de ponta a ponta, incluindo os erros | `@SpringBootTest` | segundos |
| **Carga** | Throughput e latência sob pressão | Gerador sintético (ADR-005) | sob demanda |

Os três primeiros rodam **sem subir nada**. Isso não é detalhe de conveniência — é o que
permite rodá-los a cada salvamento de arquivo, e teste que roda a cada salvamento é teste que
pega o erro no minuto em que ele nasce.

---

## 3. O teste de propriedade, explicado do zero

### 3.1 A diferença

Um **teste de exemplo** é você conferir três contas na calculadora: "se eu mandar `3E 80`, tem que
sair 4.000 rpm". Ele prova que aquele caso funciona. Não prova nada sobre os outros 65.535.

Um **teste de propriedade** é diferente: em vez de escolher os casos, você declara uma **regra que
deve valer sempre**, e a biblioteca gera centenas ou milhares de entradas aleatórias tentando
quebrá-la. Quando acha uma que quebra, ela ainda faz *shrinking* — reduz o contraexemplo até o
menor caso que ainda falha, e te entrega isso em vez de um número aleatório gigante.

A analogia mais próxima: teste de exemplo é conferir três medições; teste de propriedade é dizer
*"a soma das forças num corpo em repouso tem que dar zero"* e mandar o computador tentar dez mil
configurações procurando uma em que não dê.

### 3.2 A propriedade do decodificador

O decodificador tem uma inversa natural — o codificador, que é o que o firmware faz. Isso dá uma
propriedade de ida e volta.

**Propriedade 1 — exata.** Para *qualquer* padrão de bits que caiba na largura do sinal,
decodificar e recodificar tem que devolver os mesmos bits:

```
encode(decode(bits)) == bits
```

Esta é a mais forte, porque é **igualdade exata** e cobre todo o espaço de entrada — inclusive os
extremos que ninguém pensa em testar à mão: tudo zero, tudo um, o bit de sinal ligado sozinho.

**Propriedade 2 — aproximada, e o "aproximada" é o ponto.** Partindo de um valor físico:

```
| decode(encode(valor)) − valor |  ≤  escala / 2
```

Não dá para exigir igualdade aqui, e entender por quê é entender a quantização: com escala 0,25,
os valores representáveis são 0; 0,25; 0,50… Um RPM de 4.000,10 **não existe** no barramento —
ao codificar, ele vira 4.000,00. A volta nunca vai devolver 4.000,10.

O erro máximo é metade do passo de quantização. **Testar com `==` aqui daria um teste que falha
sempre; testar com uma tolerância inventada daria um teste que passa sempre.** A tolerância certa
sai da escala do próprio sinal, lida do DBC.

### 3.3 Como fica

```kotlin
@Test
fun `ida e volta preserva os bits, para qualquer padrao`() = runBlocking {
    checkAll(Arb.int(0..0xFFFF)) { bitsBrutos ->
        val sinal = dbc.signal(canId = 256, nome = "rpm")
        val fisico = sinal.decode(bitsBrutos)
        sinal.encode(fisico) shouldBe bitsBrutos
    }
}
```

Cada execução são centenas de casos. Se falhar, o relatório aponta o menor valor que quebra —
e é aí que se descobre que o bit inicial estava um a mais.

### 3.4 O limite do teste de propriedade — medido, não suposto

**A ida e volta sozinha não basta**, e isso foi comprovado sabotando o código de propósito no
checkpoint 2.4.

O codificador e o decodificador compartilham a mesma função de posições de bit. Se ela estiver
errada, estará errada **dos dois lados** — e a ida e volta fecha perfeitamente num valor errado.

Introduzindo um off-by-one no ramo *little endian* (que mantém os sinais dentro do payload):

| Teste | Pegou? |
|---|---|
| Propriedade `encode(decode(bits)) == bits` | ❌ **passou** — o erro é simétrico |
| Exemplo contra os bytes do `cantools` | ✅ falhou |

> **Conclusão prática:** teste de propriedade cobre o espaço de entrada; teste de exemplo contra
> uma **implementação independente** cobre o entendimento do formato. Os dois são necessários, e
> por motivos diferentes. Um decodificador só com teste de propriedade pode estar internamente
> consistente e completamente errado.

Sabotagens que a propriedade **pegou** sozinha: extensão de sinal com largura fixa em vez da
declarada, e erros de posição que jogam bits para fora do DLC.

---

## 4. Os casos que o decodificador é obrigado a passar

O DBC provisório não tem três frames por acaso: cada um materializa uma armadilha do
[`docs/01 §4`](01-dominio-can.md). **Nenhum checkpoint da Fase 2 fecha sem os três.**

| Frame | O que exercita | O bug que pega |
|---|---|---|
| `0x100` MOTOR | Alinhado a byte, unsigned, big endian | Endianness trocada — o caso que já apareceu na documentação e virou 8.207,5 rpm |
| `0x200` DINAMICA | `speed` de 12 bits + `gear` de 4, little endian | Off-by-one no bit inicial e máscara errada em sinal que cruza fronteira de byte |
| `0x300` GPS | 32 bits **signed**, escala 1e-7 | `signed` tratado como `unsigned`: latitude do hemisfério sul viraria um número positivo enorme |

Mais dois casos que não são de propriedade e sim de comportamento:

**Fora de faixa vira `is_valid = false`, não exceção e não descarte.** Sensor desconectado manda
`0xFF` em tudo. O ponto tem que ser **gravado e marcado**, porque saber que o sensor caiu às
09:52 é informação, e um buraco no gráfico não distingue "sensor morreu" de "carro parado".

**Diretiva DBC não suportada derruba a subida da aplicação.** O parser não pode pular linha em
silêncio: ignorar um `SG_` desconhecido perde um sinal inteiro sem ninguém notar (ADR-006). O
teste alimenta um DBC com `VAL_` e espera a exceção.

---

## 5. O teste de arquitetura

A regra do [ADR-009](02-decisoes-tecnicas.md) — `domain` não importa framework — só sobrevive se
tiver fiscal. Um teste ArchUnit quebra o build se alguém anotar uma classe de domínio por pressa,
**inclusive o Claude numa sessão futura**. Está detalhado no
[`docs/07 §6`](07-arquitetura-do-codigo.md).

---

## 6. Testcontainers sem pagar o preço duas vezes

O [ADR-003](02-decisoes-tecnicas.md) já decidiu Postgres real em vez de H2. O risco prático é a
suíte ficar tão lenta que ninguém rode.

**A regra: um container para a suíte inteira, não um por classe de teste.** ✅ **Verificado** no
checkpoint 1.4 — observando os eventos do Docker durante a suíte completa, foi criado
**exatamente um** container `timescale/timescaledb`, para as 27 execuções de teste. Em Spring Boot isso é
uma classe base com o container em `companion object` e `@ServiceConnection`, ou o modo *reuse* do
Testcontainers. Subir um Postgres por classe transformaria 20 classes em 20 bootstraps.

**O schema vem do Flyway, não de `ddl-auto`.** O teste tem que exercitar exatamente as migrations
que vão para produção — inclusive o `create_hypertable`. Um teste contra um schema gerado pelo
framework testaria um banco que não existe em lugar nenhum.

---

## 6.1 Teste de concorrência pode se enganar sozinho

Descoberto no checkpoint 2.6, e vale como regra geral.

O `IdempotenciaTest` dispara 8 requisições simultâneas com o mesmo `batchId` para provar a
afirmação do [ADR-008](02-decisoes-tecnicas.md): a proteção tem que estar no banco, porque um
`if (jaExiste)` no código tem uma fresta.

**A primeira versão do teste passava mesmo com o `if` ingênuo.** O motivo não estava no teste de
concorrência em si: o serviço faz `upsert` da **sessão** antes de checar o lote, e as 8 threads
usavam a mesma sessão. A primeira segurava a linha até commitar e as outras sete ficavam na fila
— quando chegavam na verificação do lote, a primeira já tinha terminado. **A concorrência que se
queria testar nunca acontecia.**

Criando a sessão antes da largada, o teste passou a discriminar:

| Implementação | Resultado |
|---|---|
| `ON CONFLICT DO NOTHING` (a correta) | 8 respostas, 1 gravou, 7 `duplicate` |
| `if (jaExiste)` no código | 2 respostas, **6 `DuplicateKeyException`** |

> **A regra:** num teste de concorrência, verifique que o ponto disputado é realmente o que você
> quer testar. Qualquer lock anterior no mesmo fluxo serializa tudo e transforma o teste numa
> sequência. A única forma confiável de saber é **sabotar a implementação e conferir que o teste
> reprova**.

---

## 7. O caminho de erro é teste de primeira classe

Cada linha do catálogo do [`docs/08 §3`](08-contrato-de-erros.md) é um caso de teste — e o que se
verifica **não é o código HTTP**, é o campo `retryable`.

O raciocínio: um 503 que responde sem `retryable` faz o firmware apagar um buffer que deveria ter
guardado. O dado some do cartão e nunca chegou ao servidor. **É a falha mais cara que este sistema
pode ter, e ela mora no caminho de erro** — justamente a parte que a maioria dos projetos não
testa.

---

## 8. O que deliberadamente não é testado

**Que o Spring injeta dependência.** É o framework, não o nosso código.

**Getters, setters e `data class`.** O compilador do Kotlin já garante.

**Cobertura como meta numérica.** Perseguir 80% incentiva testar o que é fácil de testar — que é
exatamente o encanamento — e deixa o decodificador de lado porque ele dá trabalho. A meta aqui é
por área de risco, não por percentual: **o decodificador e o caminho de erro têm que estar
cobertos**, e o resto segue o bom senso.
