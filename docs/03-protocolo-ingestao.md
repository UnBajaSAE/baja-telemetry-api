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
    { "t": 1756041600123, "id": 256, "data": "3E805B0000000000" },
    { "t": 1756041600133, "id": 256, "data": "3E925C0000000000" }
  ]
}
```

| Campo | Tipo | Observação |
|---|---|---|
| `batchId` | UUID | Gerado pelo dispositivo. **Chave de idempotência** |
| `deviceId` | string | Qual dos nós enviou |
| `sessionId` | string | Agrupa uma sessão de teste ou prova |
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

- [ ] Consolidar o mapa de sinais do firmware num arquivo DBC — pré-requisito do decodificador
- [ ] Definir se `sessionId` é criado pelo dispositivo ou pela API
- [ ] Estratégia de correção de deriva do relógio do ESP32
- [ ] Rotação de API key por dispositivo
