# 01 · O domínio: do byte cru à grandeza física

Este documento explica o que exatamente chega na API. Sem entender isso, todo o resto do
projeto — modelo de dados, decodificador, volume — fica arbitrário.

---

## 1. O que é um frame CAN

O CAN (*Controller Area Network*) é o barramento que as ECUs do carro usam pra conversar. A
unidade de transmissão dele é o **frame**, e o frame é minúsculo:

| Campo | Tamanho | O que é |
|---|---|---|
| Identificador | 11 bits (CAN 2.0A) | Diz *que tipo* de mensagem é esta |
| DLC | 4 bits | Quantos bytes de dado vêm (0 a 8) |
| Payload | 0 a 8 **bytes** | O dado em si |

Oito bytes. É todo o espaço disponível. Não existe nome de campo, não existe JSON, não existe
nada legível. O que trafega no fio é isto:

```
ID: 0x100   DLC: 8   Payload: 3E 80 5B 00 00 00 00 00
```

Um observador externo não tem como saber o que esses bytes significam. **O significado não está
no dado — está numa tabela combinada de antemão entre quem envia e quem recebe.**

Essa é a diferença mais importante entre CAN e uma API HTTP. Em JSON, o dado se autodescreve
(`{"rpm": 4000}`). No CAN, o dado é opaco e o significado é externo. É por isso que existe DBC.

---

## 2. Um frame carrega vários sinais

O frame acima não transporta uma informação — transporta duas:

```
ID: 0x100   Payload: 3E 80 5B 00 00 00 00 00
                     └──┬──┘ └┬┘
                        │     └── byte 2    → temperatura
                        └──────── bytes 0-1 → RPM
```

Isso não é acidente nem economia gratuita. O CAN tem banda limitada — tipicamente 500 kbit/s no
Baja — e **cada frame carrega overhead fixo** (identificador, CRC, bits de sincronismo, ACK).
Mandar dois frames de 2 bytes custa muito mais que um frame de 4 bytes, porque o overhead é pago
duas vezes.

Por isso a prática da indústria é agrupar no mesmo frame os sinais que fazem sentido juntos:
mesma taxa de atualização, mesma ECU de origem, mesmo contexto de uso.

> **Consequência para a API:** a unidade que chega pela rede (**frame**) não é a unidade que se
> consulta (**sinal**). Um `POST /ingest` com 1.000 frames pode gerar 2.500 pontos de sinal. Essa
> assimetria é justamente o que o decodificador resolve.

---

## 3. Como um byte vira grandeza física

Aqui está o passo que quase todo mundo entende errado na primeira vez.

### 3.1 O RPM

```
bytes 0-1 = 0x3E 0x80
```

Primeiro, junta os dois bytes num inteiro de 16 bits. A ordem importa: em **big endian**
(*byte mais significativo primeiro*, a convenção mais comum no CAN automotivo), fica:

```
0x3E80 = (0x3E << 8) | 0x80 = (62 × 256) + 128 = 16.000
```

Dezesseis mil. Só que o motor não gira a 16.000 rpm — um motor de Baja gira na casa dos 4.000.
Falta aplicar a **escala**:

```
rpm = 16.000 × 0,25 = 4.000 rpm
```

**Por que 0,25 e não guardar 4000 direto?** Porque a escala compra resolução de graça. Com
16 bits sem escala, você representa 0 a 65.535 rpm em passos de 1 — desperdiça mais da metade da
faixa (o motor nunca passa de ~6.000) e não consegue representar 4.000,5. Com escala 0,25, a
faixa vira 0 a 16.383 rpm em passos de **0,25 rpm**. Mesmos 16 bits, quatro vezes mais precisão
na faixa que interessa.

A regra geral: **a escala move a precisão para onde o fenômeno realmente acontece.**

### 3.2 A temperatura

```
byte 2 = 0x5B = 91
temp = 91 − 40 = 51 °C
```

Um byte guarda 0 a 255, sem sinal — não existe número negativo ali. Mas temperatura de motor
pode ser negativa (parado numa manhã fria, ou um sensor de ar de admissão). O truque é o
**offset**: desloca-se toda a faixa para baixo.

| Byte cru | Valor físico |
|---|---|
| 0 | −40 °C |
| 40 | 0 °C |
| 91 | 51 °C |
| 255 | 215 °C |

Um único byte cobre de −40 °C a +215 °C, faixa mais que suficiente, sem gastar bit de sinal.

> **−40 não é número mágico:** é onde as escalas Celsius e Fahrenheit se cruzam (−40 °C = −40 °F),
> e virou convenção automotiva justamente por isso.

### 3.3 A fórmula geral

Todo sinal CAN, sem exceção, sai desta fórmula:

```
valor_físico = (valor_cru × escala) + offset
```

E a inversa, que o firmware usa pra codificar:

```
valor_cru = (valor_físico − offset) / escala
```

**Estas duas fórmulas são o coração do decodificador.** Se elas estiverem certas e bem testadas,
o resto do projeto é encanamento.

Note que elas formam um par: `decode(encode(x)) == x` deve valer para qualquer `x` na faixa
válida (a menos do arredondamento da quantização). Essa propriedade é o melhor teste automatizado
que existe pro decodificador — vale gerar milhares de valores aleatórios e verificar o ida-e-volta.

---

## 4. Os detalhes que mordem

Quatro coisas que parecem detalhe e quebram o decodificador na prática:

**Endianness.** `0x3E80` big endian é 16.000; little endian (`0x803E`) é 32.830. Errar isso não
gera erro — gera **dado silenciosamente errado**, que é muito pior. Precisa estar explícito na
definição de cada sinal.

**Sinais que não se alinham a byte.** Um sinal de 12 bits pode começar no bit 4 do byte 1 e
terminar no bit 7 do byte 2. Extrair exige deslocamento e máscara de bits, não fatiar array. É a
fonte nº 1 de bug em decodificador.

**Com e sem sinal.** O mesmo byte `0xFF` é 255 como *unsigned* e −1 como *signed* (complemento de
dois). Precisa estar declarado.

**Faixa válida.** Sensor desconectado costuma mandar `0xFF` em tudo. Sem validação de mínimo e
máximo, isso vira "temperatura de 215 °C" no gráfico e alguém entra em pânico no box. O
decodificador deve marcar o ponto como inválido, não descartá-lo silenciosamente.

---

## 5. DBC: a tabela que dá significado

A tabela que diz *"no frame `0x100`, os bits 0 a 15 são RPM, big endian, unsigned, escala 0,25,
offset 0, unidade rpm, faixa 0 a 8.000"* tem formato padrão na indústria automotiva: o **DBC**
(criado pela Vector, virou padrão de fato).

Um trecho de DBC é assim:

```
BO_ 256 MOTOR: 8 ECU_MOTOR
 SG_ rpm  : 0|16@1+ (0.25,0)  [0|8000]  "rpm" Telemetria
 SG_ temp : 16|8@1+ (1,-40)   [-40|215] "degC" Telemetria
```

Traduzindo a linha do RPM: começa no bit 0, tem 16 bits de largura, `@1+` é a notação de
byte order e de ser unsigned, `(0.25,0)` é escala e offset, `[0|8000]` é a faixa válida.

**Você quase certamente já tem essa informação** — só que espalhada no firmware, em `#define` e
em `struct`, em vez de num arquivo. Consolidá-la é o primeiro passo real do projeto, e traz um
ganho que vale por si só: **uma única fonte de verdade** para firmware, API e dashboard. Hoje, se
alguém muda a escala do sensor de combustível no firmware e esquece de avisar, o gráfico mente e
ninguém descobre.

---

## 6. Resumo operacional

O que a API precisa saber fazer com o que chega:

1. Receber lotes de frames crus (`{timestamp, canId, payload}`)
2. **Persistir o frame cru antes de qualquer processamento** — ver decisão ADR-001
3. Consultar a definição de sinais para aquele `canId`
4. Para cada sinal: extrair os bits, aplicar `(cru × escala) + offset`, validar a faixa
5. Persistir os pontos de sinal decodificados, que é o que as consultas usam
