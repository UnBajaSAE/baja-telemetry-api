# 03 · Protocolo de ingestão (ESP32 → API)

Contrato entre o firmware e o backend. Rascunho da Fase 1 — sujeito a mudança até a primeira
implementação funcionar de ponta a ponta.

---

## Princípio

O ESP32 é um cliente **não confiável por natureza**: perde conexão, tem relógio impreciso,
reinicia sem aviso e pode reenviar o que já mandou. O protocolo assume tudo isso desde o início,
em vez de tratar como exceção.

---

## `POST /api/v1/ingest`

### Requisição

```http
POST /api/v1/ingest
Content-Type: application/json
X-API-Key: <chave do dispositivo>
```

```json
{
  "batchId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceId": "esp32-node-1",
  "sessionId": "2026-08-24-teste-suspensao",
  "frames": [
    { "t": 1787572800000, "id": 256, "data": "3E805B0000000000" },
    { "t": 1787572800010, "id": 256, "data": "3E925C0000000000" }
  ]
}
```

| Campo | Tipo | Observação |
|---|---|---|
| `batchId` | UUID | Gerado pelo dispositivo. **Chave de idempotência** |
| `deviceId` | string | Qual dos nós enviou |
| `sessionId` | string | Agrupa uma sessão de teste ou prova. **Gerado pelo dispositivo**, formato `AAAA-MM-DD-slug` ([ADR-007](02-decisoes-tecnicas.md)) |
| `frames[].t` | epoch ms | Horário **da leitura no dispositivo**, não do envio |
| `frames[].id` | int | Identificador CAN (`0x100` = 256) |
| `frames[].data` | hex | Payload, 1 a 8 bytes |

### Resposta

```json
{ "batchId": "550e8400-…", "framesReceived": 2, "framesStored": 2, "duplicate": false }
```

Lote já processado responde **200** com `"duplicate": true` — não 409. Do ponto de vista do
cliente o resultado é o mesmo (o dado está lá), e devolver erro faria o firmware tentar
"consertar" algo que já está certo.

Frames individuais inválidos **não derrubam o lote** — os válidos entram e os rejeitados são
reportados ([ADR-010](02-decisoes-tecnicas.md)):

```json
{ "batchId": "550e8400-…", "framesReceived": 1000, "framesStored": 999,
  "framesRejected": 1, "duplicate": false,
  "rejections": [ { "index": 447, "reason": "malformed-frame", "detail": "…" } ] }
```

> **O catálogo completo de erros — e, mais importante, quando o firmware deve retentar e quando
> deve apagar o buffer — está em [`docs/08-contrato-de-erros.md`](08-contrato-de-erros.md).**
> É a referência do lado do firmware.

### Limites

| Regra | Valor |
|---|---|
| Frames por lote | máx. 5.000 |
| Tamanho do corpo | máx. 1 MiB |
| Payload por frame | 1 a 8 bytes |
| Idade máxima do `t` | 7 dias |

---

## Por que hex e não binário

JSON com payload em hexadecimal gasta ~2,3× mais banda que binário puro (Protobuf, CBOR). Ainda
assim, hex ganha na Fase 1:

- Depurável com `curl` e legível no log — decisivo enquanto o decodificador ainda tem bug
- Firmware gera com `sprintf`, sem biblioteca extra no ESP32
- A banda só passa a ser gargalo em ordens de grandeza acima desta

Se o teste de carga da Fase 5 mostrar que a serialização é o gargalo, migra-se para um formato
binário — decisão a ser tomada **com o número medido em mãos**, não por antecipação.

---

## Comportamento do cliente (firmware)

```
loop:
  ler frame do barramento CAN
  gravar no buffer do cartão SD

  se (buffer >= 1000 frames) ou (passaram 5 s):
      montar lote com batchId novo
      tentar POST

      se 2xx:  apagar do buffer
      se erro: manter no buffer, backoff exponencial, tentar de novo
               (o MESMO batchId — é isso que torna o reenvio seguro)
```

**O ponto crítico:** em caso de falha, reenviar com o **mesmo** `batchId`. Gerar um novo a cada
tentativa destrói a idempotência e duplica dados.

### Dois requisitos de persistência no cartão SD

Ambos saem do [ADR-008](02-decisoes-tecnicas.md) e valem a pena por escrito, porque não se deduzem
lendo só a API — são do lado do firmware:

| O que gravar no SD | Por que não basta em memória |
|---|---|
| O **`batchId`**, junto com o buffer que ele identifica | O ESP32 reinicia. Se o id for regenerado, o mesmo dado volta com id novo, a proteção de idempotência não reconhece, e duplica |
| O **`sessionId`** corrente | Mesmo motivo: reinício no meio da coleta não pode fatiar a sessão em duas ([ADR-007](02-decisoes-tecnicas.md)) |

> A idempotência da API é forte para reenvio, e **depende** dessas duas gravações para cobrir
> reinício. Sem elas, existe um cenário de duplicação que o backend não tem como detectar.

---

## Endpoints de consulta (Fase 3)

Esboço:

| Endpoint | Retorna |
|---|---|
| `GET /api/v1/sessions` | Lista de sessões com duração e contagem |
| `GET /api/v1/sessions/{id}/summary` | Máximo, média e duração por sinal |
| `GET /api/v1/sessions/{id}/metrics?signal=rpm&from=&to=&bucket=1s` | Série temporal agregada por janela |
| `GET /api/v1/sessions/{id}/frames?cursor=` | Frames crus, paginado por cursor |

Paginação por **cursor**, não por offset: `OFFSET 1000000` obriga o banco a contar e descartar um
milhão de linhas antes de devolver a página. Cursor sobre a coluna de tempo indexada vai direto
ao ponto.

---

## Em aberto

- [x] ~~Consolidar o mapa de sinais num arquivo DBC~~ — feito, [`docs/05`](05-mapa-de-sinais.md).
      O arquivo existe e é válido; os **valores** ainda são fictícios até o levantamento do firmware
- [x] ~~Definir se `sessionId` é criado pelo dispositivo ou pela API~~ — **dispositivo**, [ADR-007](02-decisoes-tecnicas.md)
- [ ] Estratégia de correção de deriva do relógio do ESP32 — o schema já guarda os dois relógios
      ([`docs/06 §2`](06-modelo-de-dados.md)); falta o algoritmo
- [ ] Rotação de API key por dispositivo — Fase 4
- [x] ~~Catálogo de erros e comportamento de retentativa~~ — [`docs/08`](08-contrato-de-erros.md)
