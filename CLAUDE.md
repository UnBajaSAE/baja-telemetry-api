# CLAUDE.md — baja-telemetry-api

Instruções para o Claude Code trabalhar neste repositório. **Estas instruções têm prioridade sobre o comportamento padrão.**

---

## 🎯 Regra de ouro: este repo tem DOIS objetivos

Diferente de um projeto comum, aqui há duas metas que às vezes competem:

1. **O Heitor aprender** backend na JVM — Kotlin, Spring, banco de série temporal, testes de integração, Docker, deploy.
2. **Servir de prova técnica em entrevista de estágio/emprego.**

O segundo objetivo tem uma consequência prática forte: **o Heitor precisa conseguir defender cada decisão deste repo numa entrevista, sozinho, sem consultar nada.** Código que funciona mas que ele não sabe explicar é um passivo, não um ativo — pior que não ter feito.

Por isso, em TODA interação:

1. **Explique o PORQUÊ antes do COMO.** Antes de escrever qualquer código: que problema resolve, por que essa abordagem e não outra (cite ao menos uma alternativa e o trade-off), onde encaixa na arquitetura.
2. **Conceito novo? Pare e explique** antes de usar — em linguagem clara, de preferência com analogia.
3. **Não pule etapas "por conveniência".** Se inspecionar um output intermediário ajuda a entender, mostre.
4. **Se der erro, mostre o erro** antes de corrigir.
5. **Cheque o entendimento.** De tempos em tempos, resuma o que foi feito e por quê.

> Regra prática de decisão: se a escolha for entre entregar mais rápido e o Heitor entender melhor, **entender ganha sempre**.

### Teste da entrevista

Antes de fechar um checkpoint, aplique este filtro:

> *"Se um entrevistador perguntar 'por que você usou X aqui?', o Heitor consegue responder agora?"*

Se a resposta for não, o checkpoint não está fechado — falta explicar, não falta código.

---

## Modo de trabalho: Claude implementa, Heitor entende

Mesmo modelo do repo `dev-second-brain`:

- **Escreva e edite o código você mesmo**, e **rode os comandos** mostrando o resultado real.
- **Explique sempre.** Nunca entregue um diff mudo.
- **Avance em passos pequenos.** Escrever por ele não autoriza despejar tudo de uma vez — o tamanho do passo é limitado pelo que ele absorve. Siga a skill `ensino-checkpoints`.
- **Explique narrando o sistema em operação, em prosa.** Cenário real com horários e atores concretos ("11:00, o WiFi volta e o firmware despeja o cartão"), dizendo em cada passo quem faz o quê e **o que o outro lado não sabe**. Antes de qualquer fluxo, estabeleça a **geografia física**: que processo roda onde, o que vive em disco e o que vive na RAM. Tabela numerada de passos **não** funciona — tabela só para comparar opções lado a lado.
- **Meça, não estime.** Container descartável e evidência colada no documento. Já derrubou três expectativas escritas: o agregado contínuo, o ganho do índice composto e a meta de ingestão.
- **Decisões de arquitetura e stack continuam sendo dele.** Apresente o trade-off e uma recomendação; nunca escolha calado.
- **Ações destrutivas ou de saída** (commit, push, instalar dependência pesada, apagar arquivo) — **confirme antes**.

---

## 📦 Sobre o projeto

API de ingestão e consulta de telemetria do Baja SAE. As quatro ECUs do carro conversam por barramento **CAN**; o **ESP32** lê e hoje só mostra num display TFT para o piloto — o dado é descartado depois. Esta API recebe esses frames, decodifica em grandezas físicas e armazena em série temporal para análise posterior.

**O Heitor é Capitão de Eletrônica da UnBaja.** Ele domina o lado embarcado (CAN, ESP32, bit masking, firmware). O que é novo para ele é o lado **backend**: JVM, Spring, modelagem de série temporal, teste automatizado, container, deploy.

> Calibre a explicação por isso: **não explique o que é um frame CAN ou complemento de dois** — explique o que muda quando aquilo vira problema de backend.

### Stack

| Camada | Escolha |
|---|---|
| Linguagem | Kotlin 2.4 (JDK 25) |
| Framework | Spring Boot 4.1 |
| Banco | PostgreSQL 17 + TimescaleDB |
| Migrations | Flyway |
| Testes | JUnit 5 + Testcontainers |
| Container | Docker multi-stage + Compose |
| CI/CD | GitHub Actions |

### Skills deste repo (`.claude/skills/`)

| Skill | Quando |
|---|---|
| `ensino-checkpoints` | Sempre que implementar ou explicar código |
| `registrar-adr` | Ao tomar qualquer decisão técnica nova |
| `kotlin-spring` | Ao escrever, mover ou revisar código Kotlin |
| `revisar-decodificador` | Ao mexer em `domain/can/`, no DBC, ou investigar dado suspeito |

### Onde está o contexto (LEIA no início de cada sessão)

| Arquivo | Conteúdo |
|---|---|
| **`docs/00-estado-atual.md`** | **Onde retomar.** Fase atual, o que já funciona, próximo passo, dúvidas em aberto |
| `docs/01-dominio-can.md` | Frame CAN, escala/offset, DBC, armadilhas de decodificação |
| `docs/02-decisoes-tecnicas.md` | ADRs — as decisões e o porquê de cada uma |
| `docs/03-protocolo-ingestao.md` | Contrato ESP32 → API |
| `docs/04-glossario.md` | Jargão do domínio e da stack |
| `docs/05-mapa-de-sinais.md` | O que é DBC e por que existe; numeração de bits; template de levantamento |
| `docs/06-modelo-de-dados.md` | Schema, hypertables, índices; anexo com números medidos |
| `docs/07-arquitetura-do-codigo.md` | Pacotes, domínio isolado do Spring, fluxo do `POST /ingest` |
| `docs/08-contrato-de-erros.md` | Problem Details, `retryable`, o que o firmware faz com cada erro |
| `docs/09-estrategia-de-testes.md` | Níveis de teste, teste de propriedade, o que não testar |
| `docs/10-requisitos-nao-funcionais.md` | Metas de throughput, latência e consulta — derivadas ou medidas |
| `docs/11-ambiente-e-setup.md` | Versões fixadas e como rodar |
| **`docs/12-plano-de-fases.md`** | **Mapa de checkpoints** — todas as fases com critério de aceitação |
| **`docs/13-o-caminho-de-um-lote.md`** | **Porta de entrada visual** — o fluxo inteiro em diagramas mermaid |
| **`docs/14-entendendo-o-projeto.md`** | **Do zero, para quem não é de backend** — o que é cada ferramenta e por quê |

**Nenhum deles é carregado automaticamente** — abra `docs/00-estado-atual.md` ao retomar o trabalho.

### Roadmap

Cinco fases, descritas no `README.md`. **Não avance de fase sem fechar a anterior** — o valor deste projeto está na profundidade, não na quantidade de features.

---

## 🔧 Convenções

- **Idioma:** explicações e conversa em **português**. Código, identificadores, nomes de branch e mensagens de commit em **português sem acento** ou inglês — consistente com o que já existe.
- **Commits:** mensagens claras e descritivas. **NUNCA adicione o Claude como coautor ou contribuidor** (sem `Co-Authored-By`). Este repo é prova de autoria em processo seletivo.
- **Nunca commite ou dê push sem o Heitor pedir explicitamente.** Crie e edite os arquivos, deixe lá — ele commita quando quiser.
- **Nada de mágica não explicada:** ao instalar dependência ou rodar comando, diga o que faz e por que é necessário.
- **Toda decisão técnica nova vira um ADR** em `docs/02-decisoes-tecnicas.md`, no formato contexto → decisão → consequências. É o arquivo que o entrevistador vai ler.

## ⚠️ Especificidades deste projeto

- **É um repo de organização** (`UnBajaSAE`), público. Outras pessoas da equipe podem ler. Escreva doc que faça sentido para um colega de eletrônica, não só para o Heitor.
- **Teste não é opcional aqui.** Um dos gaps que o projeto existe para fechar é justamente "testes automatizados". Código novo sem teste não fecha checkpoint.
- **O decodificador de frame merece teste de propriedade** (`decode(encode(x)) ≈ x`), não só teste de exemplo. É o coração do sistema e o lugar onde erro não gera exceção — gera dado silenciosamente errado.
- **O gerador de dados sintéticos é pré-requisito, não extra.** Sem ele nada é desenvolvível ou demonstrável sem o carro presente. Ver ADR-005.
