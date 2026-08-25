---
name: ensino-checkpoints
description: Metodologia de ensino do Heitor neste projeto — avançar por checkpoints pequenos e verificáveis, com o Claude escrevendo o código e o Heitor entendendo o que foi feito e por quê. Use SEMPRE que for implementar ou explicar código neste repo, e quando ele disser "vamos seguir", "próximo checkpoint/passo", ou pedir para entender alguma parte do código.
---

# Ensino por checkpoints

Como o Heitor aprende neste repo. O objetivo é o **entendimento**, não a velocidade de entrega.

## Princípio central

**O Claude escreve o código; o Heitor entende o que está acontecendo.**

Os checkpoints são **pequenos**. Escrever o código por ele não é licença para despejar o
projeto inteiro de uma vez — o limite é quanto ele consegue absorver num passo, não quanto dá
para implementar.

## O que torna ESTE repo diferente

Este projeto tem função dupla: aprender **e** servir de prova técnica em entrevista de
estágio. Isso muda o critério de "pronto".

> **Teste da entrevista.** Antes de fechar um checkpoint:
> *"Se um entrevistador perguntar 'por que você usou X aqui?', o Heitor consegue responder agora?"*
> Se não, o checkpoint não está fechado — falta explicar, não falta código.

E muda também o ponto de partida da explicação:

- **Ele é Capitão de Eletrônica da UnBaja.** CAN, ESP32, máscara de bit, complemento de dois,
  firmware — ele domina. **Não explique isso.**
- **O que é novo é o backend:** JVM, Spring, injeção de dependência, modelagem de série
  temporal, teste automatizado, container, deploy.
- Ao tocar no domínio, o ângulo útil é **"o que muda quando isso vira problema de backend"**,
  não "o que é isso".

## O ciclo de um checkpoint

1. **Porquê antes do como.** Que problema o código resolve, por que essa abordagem (com ao
   menos uma alternativa e o trade-off), onde encaixa na arquitetura.
2. **Conceito novo? Pare e explique** em linguagem clara, com analogia quando ajudar, ANTES
   de usar.
3. **Fatie em checkpoint minúsculo.** Um checkpoint = uma ideia nova ou um incremento pequeno
   com **resultado observável**.
4. **Escreva o código e explique cada parte não-óbvia** — o que faz, por que está ali, qual
   armadilha evita.
5. **Rode você mesmo e mostre a saída real.** Ele não precisa executar nada para avançar.
6. **Aponte o que observar na saída** — o número que confirma que funcionou.
7. **Se der erro, mostre o erro** antes de corrigir. Errar em público é material de aprendizado.
8. Só então avance.

## Hábitos que valem sempre

- **Confirme a menor coisa primeiro** (subir o Postgres do compose e conectar por `psql` antes
  de escrever repositório). Isola problema de ambiente de problema de lógica.
- **Teste junto com o código, nunca depois.** Fechar gap de teste é um dos motivos deste repo
  existir. Checkpoint sem teste não fecha.
- **Decisões de design/stack: apresente o trade-off e deixe o Heitor decidir.** Dê uma
  recomendação com o porquê, nunca escolha calado.
- **Toda decisão nova vira ADR** em `docs/02-decisoes-tecnicas.md`.
- **Proponha experimentos "mexer para ver o que muda"** — e aqui há um especialmente bom:
  medir uma consulta com e sem índice, ou inserção linha a linha versus em lote, e comparar o
  tempo. Ver o número mudar de ordem de grandeza fixa o conceito melhor que qualquer
  explicação.
- **Faça perguntas de observação** ("o que acontece com essa consulta quando a tabela passar
  de 7 milhões de linhas?").
- **Prefira o visual quando ajudar.** Diagramas mermaid e tabelas comunicam arquitetura melhor
  que parágrafo.
- **Registre o progresso.** Ao fechar um checkpoint, atualize `docs/00-estado-atual.md` — é o
  que permite retomar sem perder contexto entre sessões.

## Armadilhas específicas deste domínio

Ao mexer no decodificador, lembre que **erro aqui não gera exceção — gera dado plausível e
errado**. Endianness trocada, off-by-one no bit inicial, signed declarado como unsigned: todos
produzem um número que parece válido. Por isso:

- Teste de propriedade (`decode(encode(x)) ≈ x`), não só teste de exemplo.
- Faixa válida do DBC tratada como **validação executável**, não documentação.

## Idioma

Explicações e conversa em **português**; código e identificadores em **inglês**.
