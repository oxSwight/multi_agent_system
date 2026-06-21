# MIDAS: AI-Driven Software Generation Pipeline

MIDAS is an autonomous, multi-agent pipeline designed to architect, generate, test, and audit software components. Built on a Spring State Machine backbone, it orchestrates deterministic execution paths with rigorous quality gates and self-healing remediation loops.

## Architecture

* **Spring State Machine Orchestration:** Declarative topology guarantees consistent routing and validation states.
* **Immutable Context (`MidasContext`):** Ensures thread-safe, tamper-proof state management across all execution stages.
* **Dynamic Role-Based Slicing:** Context reducers limit payload scopes to optimize performance and data isolation.
* **Automated Remediation:** Bounded feedback loops automatically isolate and resolve QA and SecOps audit failures.

## Tech Stack
* **Core:** Java 21, Spring Boot 3.x, Spring State Machine
* **Persistence:** PostgreSQL, Flyway, Spring Data JPA
* **Orchestration Integration:** Dynamic LLM routing (FinOps engine)
* **Interface:** Telegram Bot API
