# ClaimsFlow360 — Insurance Claims Processing Platform

**Project #1 of the 5-project Senior Cloud / Platform Architect portfolio.**
Domain: **BFSI (Insurance)** · Architecture: **Modular Monolith + CQRS** · Complexity: **Enterprise-Grade**

---

## Strategic Purpose

This is the **anchor project** for the portfolio. It leverages the exact stack
I own day-to-day at Lincoln Financial Group (Java 21, Spring Boot 3, AWS,
OpenSearch, Bedrock, Resilience4j) so interview depth is unquestionable. It
demonstrates CQRS, DDD bounded contexts, state machines, fraud scoring, and
AI augmentation in a domain where I have firsthand production experience.

---

## Full Target Architecture (30-week vision)

> This is the eventual shape of the project across multiple build weeks. The
> **Week 1 Scope** section below scopes down to a realistic vertical slice.

### Technology Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3.3, Spring Security 6 |
| API | REST (OpenAPI 3.0), Spring WebFlux for async streams |
| Search & Analytics | AWS OpenSearch 2.x (CQRS read model) |
| Database | MySQL 8.x (write model), ElastiCache Redis 7 (caching) |
| Messaging | Amazon SQS (task queues), SNS (notifications) |
| AI / GenAI | AWS Bedrock (Claude) for summarization + fraud narratives |
| Auth | Spring Security + JWT (OAuth2 Resource Server) |
| Infra | Elastic Beanstalk / ECS Fargate, S3, CloudWatch, Secrets Manager |
| CI/CD | GitLab CI, Maven, JUnit 5, Mockito, JaCoCo |

### Functional Requirements (full vision)

- **FR-01 Claims Ingestion** — web portal + email S3 + partner APIs, validation, dedup, Bedrock doc classification.
- **FR-02 Workflow State Machine** — `SUBMITTED → UNDER_REVIEW → ADJUDICATION → APPROVED/DENIED → PAYMENT/APPEAL` with auditable transitions + rules-engine auto-adjudication.
- **FR-03 AI Summarization** — Bedrock Claude generates structured summaries (facts, coverage match, red flags) stored in OpenSearch.
- **FR-04 Fraud Detection** — 15+ rule-based indicators, SIU routing above threshold.
- **FR-05 Customer360** — aggregated policyholder view powered by OpenSearch faceted search.
- **FR-06 Real-Time Dashboard** — WebSocket live metrics.
- **FR-07 Document Management** — S3 + Textract OCR.
- **FR-08 Notifications** — email/SMS/in-app with SQS DLQ retries.

### Non-Functional Requirements

| Category | Requirement | Target |
|---|---|---|
| Performance | API p99 latency | < 200ms read, < 500ms write |
| Throughput | Claims ingestion | 500 claims/min sustained |
| Availability | Uptime SLA | 99.95% |
| Security | Auth / PII | OAuth2 JWT + RBAC, AES-256 field-level encryption (SSN/DOB) |
| Resilience | Circuit breaker | Resilience4j on all external integrations, MySQL Multi-AZ |
| Compliance | Audit | Immutable event log, 7-year retention |

### High-Level Design

Modular monolith with **CQRS**: MySQL is the write/command model, OpenSearch
is the read/query model. SQS-based event-driven synchronization ensures
eventual consistency (< 2s target lag). Four bounded contexts (Claims,
Customer, Documents, AI) communicate via domain events. An Anti-Corruption
Layer protects the core domain from external data models.

### Key Design Decisions

- **Modular monolith over microservices** — single deployable, module-level packages, but bounded-context boundaries strictly enforced via package-private visibility. Easier to run and reason about; migration to microservices is a future exercise if scale demands it.
- **CQRS via SQS + OpenSearch projection** — OpenSearch solves real query-performance problems (multi-facet search, aggregations, full-text) that MySQL struggles with at scale.
- **Transactional Outbox** — guaranteed event delivery to SQS without distributed transactions.
- **State Pattern (GoF) / Memento** — claim lifecycle with full event replay for audit.
- **Chain of Responsibility** — fraud scoring chain, dynamic rule loading.
- **Three-tier cache** — L1 Customer360 (5 min), L2 dashboard aggregations (1 min), L3 AI responses (24 h); cache-aside with circuit breaker fallthrough.

---

## 🎯 Week 1 Scope (this commit)

A runnable, tested **vertical slice** of the system. Every line is real code,
not placeholders — just narrower in surface area than the full vision.

### In scope

| Area | Included |
|---|---|
| **Domain** | `Claim` aggregate, `ClaimStatus` enum state machine, immutable `ClaimEvent` audit log |
| **State machine** | `SUBMITTED → UNDER_REVIEW → ADJUDICATION → APPROVED` happy path + `DENIED` from any non-terminal state. Invalid transitions rejected at the aggregate level. |
| **Fraud scoring** | Chain of Responsibility with 3 indicators: `AmountThresholdIndicator`, `DuplicateClaimIndicator`, `TimingPatternIndicator`. Auto-discovery of all `FraudIndicator` beans. |
| **Application services** | `ClaimIngestionService` (FR-01), `WorkflowService` (FR-02), `ClaimQueryService` |
| **REST API** | `POST /api/v1/claims`, `GET /api/v1/claims/{ref}`, `GET /api/v1/claims/{ref}/history`, `POST /api/v1/claims/{ref}/transitions` |
| **Persistence** | Spring Data JPA, Flyway `V1__init_schema.sql` (claims, claim_events, fraud_evaluations), optimistic locking via `@Version` |
| **Security** | Spring Security 6 OAuth2 Resource Server, HS256 JWT (dev), actuator health/info public |
| **Resilience** | Resilience4j `@CircuitBreaker` + `@Retry` wired on the Bedrock stub |
| **AI integration** | `BedrockClaimsSummarizer` stub (deterministic, no AWS credentials required) |
| **Outbox / events** | `ClaimEventPublisher` stub (logs); real SQS wiring lands Week 2 |
| **Exception handling** | `@RestControllerAdvice` returning RFC-7807 `ProblemDetail` |
| **Tests** | JUnit 5 + Mockito + AssertJ — aggregate state machine, fraud chain, ingestion service, workflow service, Spring context-load |

### Deferred to later weeks

- OpenSearch read-model projection (Week 2)
- Real AWS Bedrock Runtime client + prompt assembly (Week 2)
- Real SQS + transactional outbox publisher (Week 2)
- Customer / Documents / AI bounded contexts beyond stubs (Week 3)
- WebSocket real-time dashboard (Week 3)
- Textract OCR for scanned documents (Week 4)
- SNS multi-channel notification engine (Week 4)
- Field-level AES-256 encryption for SSN/DOB (Week 4)
- Flyway migrations for the remaining 5 tables (policies, customers, claim_documents, ai_summaries, notifications)
- Testcontainers MySQL integration tests (once Docker Desktop is working)
- GitLab CI pipeline + JaCoCo coverage gate

### Week 1 patterns demonstrated

- DDD aggregate with enforced invariants
- State machine with transition validation + audit events
- Chain of Responsibility (fraud scoring)
- Constructor injection everywhere (no field injection)
- Custom domain exceptions + `@RestControllerAdvice`
- Flyway-only schema management (no `schema.sql` hacks)
- Circuit breaker + retry on external dependency surface

---

## Project Structure

```
claimsflow360/
├── pom.xml
├── docker-compose.yml                    # MySQL for local dev (Week 1 optional, uses H2 for tests)
├── README.md
└── src/
    ├── main/
    │   ├── java/com/claimsflow/
    │   │   ├── ClaimsFlow360Application.java
    │   │   ├── claims/
    │   │   │   ├── domain/
    │   │   │   │   ├── Claim.java                 # aggregate root
    │   │   │   │   ├── ClaimStatus.java           # state enum + transition map
    │   │   │   │   ├── ClaimEvent.java            # immutable audit event
    │   │   │   │   └── fraud/
    │   │   │   │       ├── FraudIndicator.java
    │   │   │   │       ├── FraudIndicatorResult.java
    │   │   │   │       ├── FraudScoreResult.java
    │   │   │   │       ├── FraudScoringChain.java
    │   │   │   │       ├── AmountThresholdIndicator.java
    │   │   │   │       ├── DuplicateClaimIndicator.java
    │   │   │   │       └── TimingPatternIndicator.java
    │   │   │   ├── application/
    │   │   │   │   ├── ClaimIngestionService.java
    │   │   │   │   ├── WorkflowService.java
    │   │   │   │   └── ClaimQueryService.java
    │   │   │   ├── infra/
    │   │   │   │   ├── persistence/
    │   │   │   │   │   ├── ClaimRepository.java
    │   │   │   │   │   └── ClaimEventRepository.java
    │   │   │   │   ├── messaging/ClaimEventPublisher.java
    │   │   │   │   └── ai/BedrockClaimsSummarizer.java
    │   │   │   └── api/
    │   │   │       ├── ClaimController.java
    │   │   │       └── dto/
    │   │   │           ├── CreateClaimRequest.java
    │   │   │           ├── TransitionRequest.java
    │   │   │           ├── ClaimResponse.java
    │   │   │           └── ClaimEventResponse.java
    │   │   └── shared/
    │   │       ├── config/SecurityConfig.java
    │   │       └── exception/
    │   │           ├── DomainException.java
    │   │           ├── ClaimNotFoundException.java
    │   │           ├── InvalidClaimTransitionException.java
    │   │           ├── DuplicateClaimException.java
    │   │           └── GlobalExceptionHandler.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/V1__init_schema.sql
    └── test/
        ├── java/com/claimsflow/
        │   ├── ClaimsFlow360ApplicationTests.java
        │   └── claims/
        │       ├── domain/ClaimTest.java
        │       ├── domain/fraud/FraudScoringChainTest.java
        │       └── application/
        │           ├── ClaimIngestionServiceTest.java
        │           └── WorkflowServiceTest.java
        └── resources/application-test.yml
```

---

## Running Locally

### Prerequisites

- **Java 21** ✅ (installed)
- **Maven 3.9+** ✅ (installed)
- **IntelliJ IDEA** — preferred IDE; open the `claimsflow360/` folder directly, it will import the Maven project
- **MySQL 8.x** — required only for the `dev` profile. Simplest option: `docker compose up -d` from this directory (Docker Desktop required). Tests use H2 in-memory and need no external DB.
- **(Optional) DB GUI** — IntelliJ Ultimate's Database tool window, or [DBeaver Community](https://dbeaver.io/), or MySQL Workbench

### Build & test (no Docker required)

```bash
mvn clean verify
```

This compiles the code and runs the full JUnit 5 test suite against an
in-memory H2 database. No MySQL, no Docker, no AWS credentials needed.

### Run against MySQL (dev profile)

```bash
docker compose up -d                # start MySQL
mvn spring-boot:run
```

The app starts on `http://localhost:8080`. Flyway auto-applies `V1__init_schema.sql`.

### Running from IntelliJ

1. **File → Open** → select `claimsflow360/` folder
2. IntelliJ imports the Maven project automatically
3. Run `ClaimsFlow360Application` (green ▶ next to `main`)
4. To run tests: right-click `src/test/java` → **Run 'All Tests'**

### Smoke test the API

With the app running (you'll need a valid HS256 JWT signed with the
`claimsflow.security.jwt-secret` from `application.yml` — any online JWT tool
works for local dev):

```bash
curl -X POST http://localhost:8080/api/v1/claims \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{
    "policyNumber": "POL-001",
    "claimantName": "Jane Doe",
    "amountClaimed": 1500.00,
    "description": "Minor fender bender"
  }'
```

---

## Interview Talking Points (Week 1 slice)

1. **Why modular monolith first?** — deployment simplicity, strict package boundaries enforce bounded contexts, and it migrates cleanly to microservices if scale demands. No distributed-transaction overhead.
2. **Why enum state machine over full GoF State pattern?** — pragmatic Week 1 decision. The enum + transition map gives identical invariant enforcement and audit characteristics with 1/5 the code. The spec's State/Memento is equivalent when events are already persisted (which they are here).
3. **Why Chain of Responsibility for fraud?** — each indicator is independent, testable in isolation, and addable without touching existing code. Spring auto-discovers all `FraudIndicator` beans — zero configuration to add a 4th, 5th, or 15th indicator.
4. **Why transactional outbox (even stubbed)?** — it's the only way to reliably publish events across a DB + external broker without 2PC. The stub already enforces the correct call sites so Week 2's real SQS writer is a drop-in.
5. **Why field-level JWT validation over issuer-URI in dev?** — no external dependency for local smoke tests. Production profile swaps in the real Cognito/Okta issuer URI.
