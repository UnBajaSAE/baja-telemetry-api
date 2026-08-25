# 08 · Contrato de erros

O que a API responde quando algo dá errado, e — o que mais importa — **o que o firmware deve
fazer com cada resposta**.

> **Este é o documento que a equipe de eletrônica consome.** A pergunta que ele responde não é
> "que código HTTP saiu", é: **posso apagar o buffer, ou preciso guardar e tentar de novo?**
> Errar isso perde dado de forma irrecuperável — apagar o cartão SD é definitivo.

---

## 1. A única pergunta que importa

O ESP32, depois de um POST, precisa decidir entre duas ações, e não há uma terceira:

| Decisão | Se estiver errada |
|---|---|
| **Apagar** o trecho do buffer | Se o servidor não tinha guardado, o dado **sumiu para sempre** |
| **Manter** e tentar de novo | Se o servidor já tinha guardado, ou o pedido é inválido, o buffer **enche até estourar** e a coleta para |

Todo erro deste catálogo existe para responder isso sem ambiguidade.

**A regra geral:**

| Família | Significado | Firmware |
|---|---|---|
| **2xx** | Deu certo — o dado está no servidor | **apaga** o buffer |
| **4xx** | O pedido está errado | **não retenta.** Tentar de novo produz o mesmo erro |
| **5xx / 429** | O servidor está com problema | **retenta** com backoff, **mesmo `batchId`** |

E um caso que confunde: **lote duplicado responde 200, não erro.** Do ponto de vista do
dispositivo o resultado é idêntico — o dado está lá — e devolver erro faria o firmware tentar
consertar algo que já está certo (ADR-004).

---

## 2. O formato: Problem Details (RFC 7807)

Sem padrão, cada endpoint inventa o próprio JSON de erro: um devolve `{"erro": "..."}`, outro
`{"message": "...", "code": 42}`, e o firmware acaba com um `if` para cada um.

A RFC 7807 padroniza isso. É só um JSON com campos de nome fixo e
`Content-Type: application/problem+json`:

```json
{
  "type":     "https://baja.unb.br/errors/batch-too-large",
  "title":    "Lote acima do limite",
  "status":   413,
  "detail":   "O lote tem 8000 frames; o maximo e 5000.",
  "instance": "/api/v1/ingest",

  "batchId":   "550e8400-e29b-41d4-a716-446655440000",
  "retryable": false
}
```

| Campo | Papel |
|---|---|
| `type` | **Identificador estável do tipo de erro.** É por ele que o firmware decide, nunca pelo texto |
| `title` | Resumo legível, fixo por tipo |
| `status` | Repete o código HTTP, útil quando o erro é logado solto |
| `detail` | Explicação **deste** caso, com os números concretos |
| `instance` | Qual requisição falhou |

### 2.1 O campo `retryable` é nosso, e é de propósito

A RFC permite campos extras. Adicionamos **`retryable`**, booleano.

Sem ele, o firmware precisaria embutir a tabela de "quais códigos HTTP são retentáveis" —
duplicando no C uma regra que é do servidor, e que ficaria desatualizada na primeira vez que um
erro novo fosse criado.

Com ele, o firmware fica assim, e **nunca mais muda**:

```c
if (status >= 200 && status < 300)  apagar_do_buffer();
else if (resposta.retryable)        backoff_e_tentar_de_novo();  // mesmo batchId
else                                registrar_erro_e_descartar();
```

Erro novo no servidor já chega com a instrução dentro. É a mesma ideia do DBC: **tirar a regra de
dentro do código e botar no dado.**

---

## 3. O catálogo

| Situação | Status | `type` | `retryable` | O que o firmware faz |
|---|---|---|---|---|
| Lote aceito | 200 | — | — | apaga o buffer |
| Lote já processado | 200 | — | — | apaga o buffer (o dado está lá) |
| Aceito com frames rejeitados | 200 | — | — | apaga o buffer; **loga os rejeitados** (§4) |
| JSON ilegível | 400 | `malformed-body` | `false` | descarta e alerta — é bug de firmware |
| Campo obrigatório ausente | 400 | `missing-field` | `false` | descarta e alerta |
| `batchId` não é UUID | 400 | `invalid-batch-id` | `false` | descarta e alerta |
| Lote acima de 5.000 frames | 413 | `batch-too-large` | `false` | **refatia** em lotes menores e reenvia |
| Corpo acima de 1 MiB | 413 | `payload-too-large` | `false` | **refatia** e reenvia |
| `sessionId` fora do formato | 422 | `invalid-session-id` | `false` | corrige o formato e reenvia |
| `t` mais velho que 7 dias | 422 | `timestamp-too-old` | `false` | descarta — o dado é velho demais |
| `t` no futuro | 422 | `timestamp-in-future` | `false` | **relógio errado** — sincroniza e recarimba |
| API key ausente ou inválida | 401 | `invalid-api-key` | `false` | alerta. Retentar não conserta |
| Excesso de requisições | 429 | `rate-limited` | **`true`** | backoff, respeitando `Retry-After` |
| Erro interno | 500 | `internal-error` | **`true`** | backoff, mesmo `batchId` |
| Banco indisponível | 503 | `storage-unavailable` | **`true`** | backoff, mesmo `batchId` |

Os `type` completos são a URL `https://baja.unb.br/errors/` mais o identificador da coluna.
A URL **não precisa existir** para o contrato funcionar — ela é identificador, não endereço; mas
publicá-la depois é gentileza com quem for depurar.

### 3.1 Os dois que merecem atenção

**`413 batch-too-large` não é retentável, mas é recuperável.** O dado é bom; o recorte é que está
errado. Refatiar e reenviar é a ação certa — e **cada fatia leva um `batchId` novo**, porque são
lotes novos. É a única situação em que gerar id novo está correto.

**`422 timestamp-in-future` denuncia relógio.** O ESP32 reiniciou e voltou com data de fábrica, ou
perdeu o sincronismo. Retentar com o mesmo carimbo repete o erro para sempre; o que resolve é
sincronizar o relógio e recarimbar o buffer.

---

## 4. A decisão difícil: um frame ruim num lote de mil

O cartão SD teve um bit invertido. Num lote de 1.000 frames, **um** tem hex ímpar. O que fazer?

| Opção | Consequência |
|---|---|
| Rejeitar o lote inteiro (400) | O firmware não tem como consertar aquele byte. Ou perde os 999 bons, ou retenta para sempre |
| **Aceitar os 999, reportar o 1** | Os bons entram. O ruim é registrado e contado |

**Decisão: aceitação parcial** ([ADR-010](02-decisoes-tecnicas.md)). A resposta fica explícita:

```json
{
  "batchId":        "550e8400-e29b-41d4-a716-446655440000",
  "framesReceived": 1000,
  "framesStored":   999,
  "framesRejected": 1,
  "duplicate":      false,
  "rejections": [
    { "index": 447, "reason": "malformed-frame",
      "detail": "campo 'data' tem numero impar de digitos hex: '3E805B00000000'" }
  ]
}
```

**Isso vale só para frames individuais dentro de um corpo legível.** Se o JSON não parseia, ou o
contrato do lote é violado (sem `batchId`, sem `sessionId`), aí é 400 e o lote inteiro cai — não
há o que salvar.

> ⚠️ **O frame rejeitado é perdido.** O firmware apaga o buffer ao ver 2xx, e o servidor não
> guardou aquele frame. É perda aceita — o frame é ilegível de qualquer forma — mas precisa ser
> **auditável**: a coluna `ingest_batch.rejected_count` registra quantos, e o log guarda o
> conteúdo. Se esse número começar a subir, o problema é físico (cartão SD ruim, alimentação
> instável), e o gráfico de rejeições é o que denuncia.

---

## 5. Um exemplo por família

**Sucesso normal**
```json
{ "batchId": "550e8400-…", "framesReceived": 1000, "framesStored": 1000,
  "framesRejected": 0, "duplicate": false }
```

**Reenvio de lote já processado** — 200, e o firmware pode apagar tranquilo
```json
{ "batchId": "550e8400-…", "framesReceived": 1000, "framesStored": 0,
  "framesRejected": 0, "duplicate": true }
```

**Erro não retentável** — 413
```json
{ "type": "https://baja.unb.br/errors/batch-too-large",
  "title": "Lote acima do limite", "status": 413,
  "detail": "O lote tem 8000 frames; o maximo e 5000.",
  "instance": "/api/v1/ingest", "retryable": false }
```

**Erro retentável** — 503, e o buffer **não** pode ser apagado
```json
{ "type": "https://baja.unb.br/errors/storage-unavailable",
  "title": "Armazenamento indisponivel", "status": 503,
  "detail": "Banco de dados fora de alcance. Tente novamente.",
  "instance": "/api/v1/ingest", "retryable": true }
```

---

## 6. Regras para quem for implementar

**Nunca vazar detalhe interno no `detail`.** Nada de *stack trace*, SQL ou nome de tabela — vira
pista para quem quiser atacar, e não ajuda o firmware em nada.

**O `type` é contrato: nunca renomear.** É por ele que o firmware decide. Erro novo ganha `type`
novo; `type` existente não muda de significado.

**Todo `5xx` precisa ser investigável.** O `detail` que vai para o dispositivo é genérico, mas o
log do servidor precisa correlacionar pelo `batchId`.

**O caminho de erro merece teste tanto quanto o de sucesso** — um 503 que responde sem
`retryable` faz o firmware apagar um buffer que deveria ter guardado. Cada linha da tabela da §3
é um caso de teste ([`docs/09`](09-estrategia-de-testes.md)).
