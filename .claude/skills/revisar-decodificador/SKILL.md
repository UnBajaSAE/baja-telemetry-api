---
name: revisar-decodificador
description: Checklist de revisão do decodificador de frame CAN e do parser DBC — endianness, sinal cruzando fronteira de byte, signed/unsigned, faixa válida e teste de propriedade. Use SEMPRE que escrever ou alterar código em domain/can/, ao mexer em contracts/can/unbaja.dbc, e ao investigar qualquer suspeita de valor errado no dado decodificado.
---

# Revisar o decodificador

> **Aqui é o único lugar do sistema onde um bug não gera exceção — gera um número plausível e
> errado**, que é gravado, aparece no gráfico, e vira decisão de engenharia.

Por isso a revisão é checklist, não leitura. Encanamento quebrado grita; decodificador quebrado
mente.

## A pergunta que orienta tudo

Não é *"esse código roda?"*. É:

> **"Se este código estiver errado, como eu ficaria sabendo?"**

Se a resposta for "alguém estranharia o gráfico daqui a três meses", falta proteção.

## Checklist

### 1. Endianness

- [ ] O byte order do código **bate com o `@0`/`@1` do DBC** para aquele sinal?
- [ ] Lembrando a pegadinha: **`@0` é big endian (Motorola), `@1` é little endian (Intel)** — a
      numeração é invertida em relação à intuição
- [ ] Em big endian, o bit inicial aponta o bit **mais** significativo; em little, o **menos**.
      Um sinal de 16 bits nos bytes 0–1 big endian começa no bit **7**, não no 0

Este erro já apareceu neste repositório: o `docs/01` trazia `0|16@1+` afirmando big endian, o que
decodifica `3E 80` como 8.207,5 rpm em vez de 4.000. **Nenhum erro é levantado.**

### 2. Sinal que cruza fronteira de byte

- [ ] Extração usa **deslocamento e máscara**, não fatiamento de array?
- [ ] Sinal de largura não múltipla de 8 (12 bits, 4 bits) tem teste próprio?
- [ ] Off-by-one no bit inicial produz valor válido — existe teste que pegaria isso?

O frame `0x200` do DBC existe só para exercitar este caso (`speed` 12 bits + `gear` 4 bits).

### 3. Signed / unsigned

- [ ] O `+`/`-` do DBC é respeitado?
- [ ] Signed é interpretado em **complemento de dois**, com extensão de sinal correta a partir da
      largura declarada — não a partir de 8/16/32?
- [ ] Existe teste com valor negativo real? O frame `0x300` (GPS) tem latitude do hemisfério sul,
      que é negativa — se tratada como unsigned, vira um número positivo enorme

### 4. Faixa válida é validação executável

- [ ] O `[min|max]` do DBC é **verificado**, não ignorado?
- [ ] Fora de faixa vira **`is_valid = false`**, não exceção e **não descarte silencioso**?

Sensor desconectado manda `0xFF` em tudo. O ponto precisa ser gravado e marcado: um buraco no
gráfico não distingue "sensor morreu" de "carro parado".

### 5. O parser DBC falha alto

- [ ] Diretiva não suportada (`VAL_`, multiplexação, `BA_`) **derruba a subida da aplicação**?
- [ ] Nenhum caminho pula linha desconhecida em silêncio?

Ignorar um `SG_` desconhecido perde um sinal inteiro sem ninguém notar (ADR-006). Fora de escopo
do parser v1 está listado em [`docs/05 §5.2`](../../../docs/05-mapa-de-sinais.md).

### 6. Teste de propriedade presente

- [ ] `encode(decode(bits)) == bits` — **igualdade exata**, sobre todo o espaço de bits
- [ ] `|decode(encode(x)) − x| ≤ escala/2` — a tolerância sai da **escala do sinal lida do DBC**,
      nunca de um número inventado

A segunda não pode usar `==`: com escala 0,25 o valor 4.000,10 não existe no barramento e vira
4.000,00 ao codificar. Igualdade daria teste que falha sempre; tolerância chutada daria teste que
passa sempre.

- [ ] Os **três** frames do DBC estão cobertos, não só o fácil?

## Segunda opinião independente

Quando houver qualquer dúvida sobre o que o DBC realmente diz, confira com uma implementação que
não é a nossa:

```bash
pipx run cantools dump contracts/can/unbaja.dbc     # layout de bits de cada frame
```

Se o nosso parser e o `cantools` discordarem sobre o mesmo arquivo, **um dos dois está errado** —
e descobrir isso agora é barato.

## Se um valor suspeito aparecer no banco

A ordem de investigação, do mais provável para o menos:

1. **O `raw_frame` correspondente** — o payload cru está lá (ADR-001). Decodifique à mão e compare
2. **Endianness e bit inicial** no DBC daquele sinal — são as duas causas mais comuns
3. **`dbc_version` da linha** — o ponto pode ter sido decodificado por um mapa antigo
4. **`is_valid`** — pode não ser bug, e sim sensor caindo

Corrigido o DBC, **reprocessar o histórico a partir do `raw_frame`**. É a capacidade que o ADR-001
comprou, e o passo que todo mundo esquece.
