---
name: registrar-adr
description: Registrar uma decisão técnica nova como ADR em docs/02-decisoes-tecnicas.md, no formato contexto → decisão → alternativas → consequências. Use SEMPRE que uma decisão de arquitetura, stack, modelagem ou protocolo for tomada neste repo — inclusive quando ela surgir no meio de outro trabalho — e quando o Heitor disser "vira ADR", "registra essa decisão" ou perguntar "por que a gente escolheu X?".
---

# Registrar um ADR

`docs/02-decisoes-tecnicas.md` é **o arquivo que o entrevistador vai ler**. Cada decisão nova
entra nele, senão ela existe só no histórico do chat e some.

## Quando acionar

Sempre que houver **escolha entre caminhos** — não quando houver só um jeito de fazer.

| Vira ADR | Não vira |
|---|---|
| Escolher TimescaleDB em vez de InfluxDB | Usar `val` em vez de `var` |
| Decidir que o dispositivo gera o `sessionId` | Nomear uma variável |
| Aceitação parcial de lote em vez de tudo-ou-nada | Extrair um método |

O teste: **existia uma alternativa defensável que foi descartada?** Se não, é convenção, e o lugar
dela é a skill `kotlin-spring`.

Um ADR pode nascer **no meio de outro trabalho** — o ADR-010 apareceu escrevendo o catálogo de
erros. Isso é normal e não é escopo furado: registre e siga.

## O formato

```markdown
---

## ADR-0NN · Título afirmativo, dizendo o que foi decidido

**Status:** aceito

### Contexto

Que problema existe. Qual é o fato do domínio que **força** a decisão — de preferência com
número. Sem isso o ADR vira preferência pessoal.

### Decisão

O que foi escolhido, em uma ou duas frases.

### Alternativas consideradas

Uma por parágrafo, cada uma com: o que era, **o argumento real a favor dela**, e por que perdeu.

### Consequências

O que melhora, o que piora, e o que passa a ser obrigatório por causa disso.
```

## As três regras que separam ADR bom de ADR decorativo

**1. Pelo menos uma alternativa, apresentada com honestidade.** Espantalho estraga o ADR — se a
alternativa é obviamente ruim, não havia decisão a tomar. A alternativa precisa ter um argumento
**de verdade** a favor. Exemplo do repo: package-by-layer foi descartado, mas o ADR-009 registra
que qualquer dev Java reconhece a estrutura na hora, e chama isso de "vantagem real, que não deve
ser subestimada".

**2. Consequência negativa obrigatória.** ADR só com pontos positivos é propaganda. Se não achar o
lado ruim, a decisão não foi entendida. Todo ADR deste repo tem um.

**3. Numeração e ordem.** Confira o último número com
`grep "^## ADR" docs/02-decisoes-tecnicas.md`, e **anexe ao fim** — a ordem é cronológica, não
temática. ADR nunca é reescrito depois; se mudar de ideia, cria-se um novo com
`**Status:** substitui o ADR-0NN`.

## Depois de escrever

- Se a decisão fechava uma linha de "Decisões em aberto" no `docs/00-estado-atual.md`, marcar lá
- Se ela cria trabalho novo (coluna no schema, requisito de firmware), registrar **no documento
  que a pessoa afetada lê** — requisito de firmware vai para o `docs/03`, não só para o ADR
- Se ela altera algo já escrito, **corrigir o documento antigo**. O ADR-010 gerou a coluna
  `rejected_count`, e o `docs/06` foi atualizado no mesmo passo

## O teste da entrevista

> *"Por que vocês fizeram X assim?"*

O Heitor consegue responder **o que foi escolhido, qual era a alternativa, e por que essa ganhou**?
Se a resposta for "porque é o padrão" ou "porque é melhor", o ADR ainda não está pronto.
