# MIDAS — AI-Driven Software Generation Pipeline

MIDAS is an autonomous, multi-agent pipeline that architects, generates, tests, and
audits software components. It runs on a Spring State Machine backbone that turns a
plain-language idea into reviewed, generated source through a deterministic sequence
of specialist agents with rigorous quality gates and self-healing remediation loops.

> **Status:** pre-1.0, actively stabilizing. The test suite is green
> (`./mvnw clean verify`) and CI runs on every push and pull request.

---

## How it works

A run advances through a fixed sequence of states, each handled by one specialist
agent. Output is sanitized and **validated before it is trusted**; failures route to
bounded remediation rather than propagating downstream.

```
USER IDEA
   │
   ▼
SYSTEM_ANALYSIS ─▶ ARCHITECTURE_DESIGN ─▶ INTEGRATION_STRATEGY
   │                                              │
   │                                              ▼
   │                                        CODE_GENERATION
   │                                              │
   │                                              ▼
   │                                        TEST_GENERATION
   │                                              │
   │                                              ▼
   │                                        SECOPS_AUDIT ──▶ (remediation loop)
   │                                              │
   │                                              ▼
   └──────────────────────────────────────▶ PRODUCT_REVIEW ─▶ COMPLETED
```

| Stage                  | Agent                   | Responsibility                          |
|------------------------|-------------------------|-----------------------------------------|
| `SYSTEM_ANALYSIS`      | System Analyst          | Turn the idea into a technical spec      |
| `ARCHITECTURE_DESIGN`  | Software Architect      | Define modules, contracts, topology      |
| `INTEGRATION_STRATEGY` | Integration Engineer    | Wire components & external integrations  |
| `CODE_GENERATION`      | Implementation Engineer | Generate source (per-file strategy)      |
| `TEST_GENERATION`      | QA Automation           | Generate the test suite                  |
| `SECOPS_AUDIT`         | SecOps                  | Audit for security/release violations    |
| `PRODUCT_REVIEW`       | Controller              | Final gate                               |

## Architecture highlights

- **Spring State Machine orchestration** — declarative topology guarantees consistent
  routing and validation states.
- **Immutable context (`MidasContext`)** — thread-safe, tamper-proof state across stages.
- **Role-based context slicing** — `ContextReducer` trims each agent's payload to only
  the upstream artifacts it needs, with a fail-closed prompt-budget guard.
- **Bounded, automated remediation** — QA and SecOps failures are isolated and retried
  within configurable limits instead of failing the whole run.

## Tech stack

- **Core:** Java 21, Spring Boot 3.x, Spring State Machine
- **Persistence:** PostgreSQL, Flyway, Spring Data JPA
- **LLM integration:** pluggable client — NOUS / OpenAI-compatible (Ollama, OpenRouter,
  LM Studio) or Google Gemini, with per-stage model routing (FinOps engine)
- **Interfaces:** REST API + Telegram Bot; a Next.js dashboard (`midas-dashboard/`)

---

## Getting started

### Prerequisites

- **JDK 21** (Temurin or Corretto)
- **Docker** + Docker Compose (only needed to run the full stack)
- An LLM endpoint — a local [Ollama](https://ollama.com) running `qwen2.5-coder:14b`
  is the zero-cost default; a Gemini or OpenRouter key also works.

You do **not** need Docker or a real database just to build and test — the suite runs
against in-memory H2.

### Build & test

```bash
git clone https://github.com/oxSwight/MIDAS.git
cd MIDAS

./mvnw clean verify        # Linux/macOS — full build + tests (what CI runs)
mvnw.cmd clean verify      # Windows
```

### Run the full stack

```bash
cp .env.example .env       # fill in the values (each is documented in the file)
docker compose up --build
```

The API comes up on `http://localhost:8080`. By default MIDAS uses the
**NOUS / OpenAI-compatible** client pointed at `host.docker.internal:11434`
(a local Ollama). To use Gemini instead, set `MIDAS_LLM_CLIENT_TYPE=GEMINI`
and provide `MIDAS_LLM_API_KEY`. See `.env.example` for every knob.

## Configuration

All runtime configuration is environment-variable driven (12-factor); see
[`.env.example`](.env.example) for the complete, documented list and
[`src/main/resources/application.yml`](src/main/resources/application.yml) for the
defaults each variable overrides.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the dev loop and PR conventions, and
[SECURITY.md](SECURITY.md) for reporting vulnerabilities. Licensed under the
[MIT License](LICENSE).
