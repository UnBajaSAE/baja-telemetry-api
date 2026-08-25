# 11 · Ambiente e setup

O que precisa estar instalado, quais versões, e por que cada uma. Escrito antes do projeto Gradle
existir, para que a Fase 1 comece sem parar para decidir versão.

---

## 1. As versões, e o porquê de cada pino

Verificadas nos repositórios oficiais em 25/08/2026.

| Peça | Versão | Por que esta |
|---|---|---|
| **JDK** | **25** (Temurin) | LTS mais recente. Um projeto novo em 2026 começando num LTS antigo já nasce com dívida |
| **Kotlin** | **2.4.10** | Última estável. A 2.4.20 está em RC — release candidate não entra em projeto que serve de prova técnica |
| **Spring Boot** | **4.1.1** | Última estável, linha 4 desde nov/2025. Decisão do Heitor; ver §1.1 |
| **Gradle** | Wrapper, **Kotlin DSL** | O wrapper fixa a versão no repositório: quem clonar roda a mesma. Kotlin DSL dá autocompletar e erro em tempo de compilação no build |
| **PostgreSQL** | **17** | Maduro, suporte pleno do Timescale, e é o que a maioria dos gerenciados oferece — o que importa para a Fase 5 |
| **TimescaleDB** | **2.29.2** | O que vem em `timescale/timescaledb:latest-pg17` |
| **Flyway** | a do Spring Boot | Schema versionado em git, nunca `ddl-auto` |
| **Testcontainers** | a do Spring Boot | Postgres real no teste ([ADR-003](02-decisoes-tecnicas.md)) |

**Imagem exata:** `timescale/timescaledb:latest-pg17` — confirmada rodando
`PostgreSQL 17.11 / timescaledb 2.29.2`.

### 1.1 O trade-off do Spring Boot 4

O README dizia "Spring Boot 3". Foi decidido subir para a linha 4.

**A favor:** um projeto novo começando numa linha que já saiu de foco envelhece antes de nascer,
e *"por que 3.x num projeto de 2026?"* é uma pergunta desconfortável numa entrevista.

**Contra, e é real:** boa parte dos tutoriais, cursos e respostas de Stack Overflow ainda cobre a
3.x. Alguns exemplos vão precisar de tradução — e como um dos objetivos deste repositório é
**aprender**, esse atrito tem custo de verdade.

Aceito conscientemente. Quando um exemplo da internet não bater, o primeiro suspeito é a diferença
de linha, não o próprio código.

---

## 2. O que precisa estar na máquina

| Ferramenta | Para quê | Obrigatória? |
|---|---|---|
| **JDK 25** | Compilar e rodar | sim |
| **Docker** | Subir o banco **e rodar os testes** | **sim** |
| **`psql`** | Inspecionar o banco à mão | recomendada |
| `pipx` | `cantools` para validar o DBC | opcional |

> ⚠️ **Docker não é opcional para testar.** O [ADR-003](02-decisoes-tecnicas.md) escolheu
> Testcontainers em vez de banco em memória, então `./gradlew test` **sobe containers de verdade**.
> Sem Docker, a suíte não roda — nem localmente, nem no CI. É o preço de o teste valer alguma
> coisa.

Não é preciso instalar Gradle: o wrapper (`./gradlew`) baixa a versão certa sozinho.

---

## 3. O `docker-compose.yml`

> Este arquivo **ainda não existe** — ele é criado no checkpoint 1.2, junto com o projeto Gradle.
> Está aqui como alvo, comentado linha a linha.

```yaml
services:
  db:
    # A imagem ja traz a extensao TimescaleDB compilada.
    # Um postgres:17 puro NAO serve - a extensao teria que ser compilada a mao.
    image: timescale/timescaledb:latest-pg17

    environment:
      POSTGRES_DB: baja
      POSTGRES_USER: baja
      # Só para desenvolvimento local. Em produção vem de variável de ambiente,
      # nunca de arquivo versionado.
      POSTGRES_PASSWORD: baja

    ports:
      # Exposto para dar para conectar com psql e com o DBeaver de fora do container.
      - "5432:5432"

    volumes:
      # Sem isso, o banco morre junto com o container e cada `down` apaga tudo.
      - baja-db:/var/lib/postgresql/data

    healthcheck:
      # A aplicação sobe mais rápido que o Postgres fica pronto. Sem healthcheck,
      # o Flyway tenta conectar num banco que ainda está iniciando e a subida falha.
      test: ["CMD-SHELL", "pg_isready -U baja -d baja"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  baja-db:
```

**A `healthcheck` não é zelo excessivo, e o motivo é mais sutil do que parece.**

A imagem oficial do Postgres sobe um **servidor temporário** para rodar a inicialização, derruba
esse servidor, e só então sobe o definitivo. O `pg_isready` responde **sim** para o temporário —
e quem conectar nessa janela é desconectado no meio do trabalho:

```
FATAL:  terminating connection due to administrator command
server closed the connection unexpectedly
```

Isso aconteceu de verdade ao validar o DDL deste projeto, e o sintoma engana: parece erro de SQL,
mas é corrida de inicialização.

**Como esperar de forma confiável:** a mensagem `database system is ready to accept connections`
aparece **duas vezes** no log — a primeira é do servidor temporário. Esperar a segunda é o critério
correto:

```bash
until [ "$(docker logs db 2>&1 | grep -c 'ready to accept connections')" -ge 2 ]; do sleep 1; done
```

Nas medições deste projeto o banco ficou pronto de verdade em ~5 s. O `healthcheck` do compose com
`retries: 10` e `interval: 5s` cobre essa janela com folga.

### 3.1 A extensão precisa ser criada uma vez

A imagem **traz** o TimescaleDB, mas cada banco precisa habilitá-lo. Isso vai na primeira migration
do Flyway, não à mão:

```sql
-- V1__extensao.sql
CREATE EXTENSION IF NOT EXISTS timescaledb;
```

À mão funcionaria no laptop e falharia no CI, onde o banco nasce vazio a cada execução.

---

## 4. Como rodar

> A partir do checkpoint 1.2. Antes disso, não há o que rodar.

```bash
# 1. sobe o banco
docker compose up -d

# 2. confirma que a extensão está lá — o menor teste possível,
#    e o que separa problema de ambiente de problema de código
psql -h localhost -U baja -d baja \
     -c "SELECT extversion FROM pg_extension WHERE extname='timescaledb';"

# 3. sobe a aplicação (o Flyway aplica as migrations na subida)
./gradlew bootRun

# 4. confere que está viva
curl localhost:8080/actuator/health
```

E os testes:

```bash
./gradlew test          # sobe containers próprios, independentes do compose
```

---

## 5. Ferramentas de bancada

Não são dependências do projeto — rodam em ambiente efêmero e servem de **segunda opinião
independente** do nosso próprio código.

```bash
# valida o DBC e mostra o layout de bits de cada frame
pipx run cantools dump contracts/can/unbaja.dbc

# valida o contrato HTTP
pipx run openapi-spec-validator contracts/openapi.yaml
```

Se o nosso parser e o `cantools` discordarem sobre o mesmo arquivo, um dos dois está errado — e é
exatamente isso que se quer descobrir cedo.

---

## 6. O que fica de fora do controle de versão

Já coberto pelo `.gitignore`, mas vale dizer o porquê:

| Ignorado | Motivo |
|---|---|
| `.gradle/`, `build/` | Gerados. Versionar cria conflito em todo merge |
| `.env`, `*.local.yml` | Segredo não entra em repositório público |
| `docker-compose.override.yml` | Ajuste pessoal de máquina, não decisão do projeto |
| `/data/`, `*.log` | Dado e log de execução local |

**O `gradle/wrapper/gradle-wrapper.jar` é a exceção que fica.** Ele é binário, mas versioná-lo é o
que faz o `./gradlew` funcionar em máquina limpa sem Gradle instalado — o `.gitignore` já tem a
regra de exceção para ele.
