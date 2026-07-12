# ClaimsFlow360 — Insurance Claims Processing Platform

An Insurance Claims Processing Platform that demonstrates CQRS, DDD bounded
contexts, state machines, fraud scoring, and AI augmentation using the exact
enterprise stack used at Lincoln Financial Group.

**Domain:** BFSI (Insurance) · **Architecture:** Modular Monolith + CQRS · **Complexity:** Enterprise-Grade

---

## Full Target Architecture

### Technology Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3.3, Spring Security 6 |
| API | REST (OpenAPI 3.0), Spring WebFlux for async streams |
| Search & Analytics | AWS OpenSearch 2.x (CQRS read model) |
| Database | MySQL 8.x (write model), ElastiCache Redis 7 (caching) |
| Messaging | Amazon SQS FIFO (task queues), SNS (notifications) |
| AI / GenAI | AWS Bedrock (Claude Converse API) for summarization + fraud narratives |
| Auth | Spring Security 6 + JWT (OAuth2 Resource Server) |
| Infra | Elastic Beanstalk / ECS Fargate, S3, CloudWatch, Secrets Manager |
| CI/CD | GitLab CI, Maven, JUnit 5, Mockito, JaCoCo |

### Functional Requirements (full vision)

- **FR-01 Claims Ingestion** — web portal + email S3 + partner APIs, validation, dedup, Bedrock doc classification.
- **FR-02 Workflow State Machine** — `SUBMITTED → UNDER_REVIEW → ADJUDICATION → APPROVED/DENIED → PAYMENT/APPEAL` with auditable transitions + rules-engine auto-adjudication.
- **FR-03 AI Summarization** — Bedrock Claude generates structured JSON summaries (facts, coverage match, red flags) stored in MySQL and projected to OpenSearch.
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

Modular monolith with **CQRS**: MySQL is the write/command model, OpenSearch is
the read/query model. SQS-based event-driven synchronization via the Transactional
Outbox pattern ensures eventual consistency (< 2 s target lag). Four bounded
contexts (Claims, Customer, Documents, AI) communicate via domain events. An
Anti-Corruption Layer protects the core domain from external data models.

### Key Design Decisions

- **Modular monolith over microservices** — single deployable, module-level packages, bounded-context boundaries enforced via package structure. Migrates to microservices if scale demands.
- **CQRS via SQS + OpenSearch projection** — OpenSearch solves real query-performance problems (multi-facet search, aggregations, full-text) that MySQL struggles with at scale.
- **Transactional Outbox** — guaranteed at-least-once delivery to SQS without a distributed transaction (no 2PC). Relay scheduler handles retry up to 3 attempts.
- **Chain of Responsibility** — fraud scoring chain; adding a 4th–15th indicator is zero-touch (Spring auto-discovers all `FraudIndicator` beans).
- **Profile-based AWS bean switching** — `@Profile("!test")` for real SQS/Bedrock/OpenSearch; `@Profile("test")` for stubs. Tests run with no AWS credentials.
- **Three-tier cache** — L1 Customer360 (5 min), L2 dashboard aggregations (1 min), L3 AI responses (24 h); cache-aside with circuit breaker fallthrough (Week 3).

---

## Build History

### ✅ Week 1 — Core Domain + REST API (commit `6688d32`)

| Area | Delivered |
|---|---|
| **Domain** | `Claim` aggregate root, `ClaimStatus` enum state machine, immutable `ClaimEvent` audit log |
| **State machine** | `SUBMITTED → UNDER_REVIEW → ADJUDICATION → APPROVED` + `DENIED` from any non-terminal state |
| **Fraud scoring** | Chain of Responsibility — 3 indicators (`AmountThreshold`, `DuplicateClaim`, `TimingPattern`); auto-discovered by Spring |
| **Application services** | `ClaimIngestionService` (FR-01), `WorkflowService` (FR-02), `ClaimQueryService` |
| **REST API** | `POST /claims` · `GET /claims/{ref}` · `GET /claims/{ref}/history` · `POST /claims/{ref}/transitions` |
| **Persistence** | Spring Data JPA + Flyway V1 schema (`claims`, `claim_events`, `fraud_evaluations`), optimistic locking (`@Version`) |
| **Security** | Spring Security 6 OAuth2 Resource Server, HS256 JWT for local dev |
| **Resilience** | Resilience4j `@CircuitBreaker` + `@Retry` wired on Bedrock stub |
| **Exception handling** | `@RestControllerAdvice` returning RFC-7807 `ProblemDetail` |
| **Tests** | 23 passing — aggregate state machine, fraud chain, ingestion + workflow services, Spring context load (H2) |

**Week 1 patterns:** DDD aggregate invariants · State machine · Chain of Responsibility · Constructor injection · Flyway-only schema management · Circuit breaker skeleton

---

### ✅ Week 2 — Transactional Outbox + SQS + OpenSearch CQRS + Bedrock (commit `c3e4835`)

| Area | Delivered |
|---|---|
| **Transactional Outbox** | `OutboxEvent` (JPA entity, `outbox_events` table via Flyway V2) written atomically in the same `@Transactional` as business writes — no 2PC |
| **`OutboxClaimEventPublisher`** | `@Profile("!test")` — replaces stub; writes JSON event to `outbox_events` within caller's transaction |
| **`OutboxRelayScheduler`** | Polls pending rows every 2 s, sends to SQS FIFO, marks `SENT`; retries `FAILED` events on a 30 s schedule (max 3 attempts) |
| **AWS SDK v2 config** | `SqsConfig`, `BedrockConfig`, `OpenSearchConfig` — all `@Profile("!test")`; `DefaultCredentialsProvider` (env vars / `~/.aws` / IAM role) |
| **OpenSearch read model** | `ClaimDocument` (denormalized index doc, keyed by `claimRef`); `ClaimSearchRepository` interface → `OpenSearchClaimSearchRepository` (real) + `NoOpClaimSearchRepository` (test) |
| **`OpenSearchClaimSearchRepository`** | Query DSL — multi-field full-text (`claimantName^3`, `policyNumber^2`, `description`, `aiSummary`) + bool filters (`status`, `fraudFlagged`). Never string-concat. |
| **`ClaimProjectionService`** | Builds `ClaimDocument` from MySQL aggregate, upserts to OpenSearch; idempotent / safe to replay |
| **Bedrock Converse API (FR-03)** | `ClaimsSummarizer` interface → `BedrockClaimsSummarizer` (real, `@Profile("!test")`) + `StubClaimsSummarizer` (test). Converse API, `temperature=0.2`, structured JSON prompt |
| **`ClaimSummarizationPromptBuilder`** | Prompt assembles claim fields + fraud score + threshold; instructs Claude to return `keyFacts`, `coverageMatch`, `redFlags`, `recommendedAction` in JSON |
| **AI persistence** | `AiSummary` JPA entity (`ai_summaries` table via Flyway V3); `AiSummarizationService` — calls summarizer, upserts result, refreshes OpenSearch projection |
| **New API endpoints** | `GET /claims?q=&status=&fraudOnly=` (OpenSearch search) · `POST /claims/{ref}/summarize` (Bedrock trigger) · `POST /claims/{ref}/project` (manual projection refresh) |
| **Tests** | 37 passing (+14 new) — outbox publisher, relay scheduler (SQS mock), projection service, Bedrock summarizer (mocked SDK client), AI summarization service. **Zero real AWS services needed.** |

**Week 2 patterns added:** Transactional Outbox · Profile-based AWS bean switching · OpenSearch Query DSL · Bedrock Converse API · Upsert AI persistence

---

### ✅ Week 3 — SQS Consumer + Reconciliation + Customer360 + WebSocket Dashboard (commit `44330cc`)

| Area | Delivered |
|---|---|
| **SQS long-poll consumer** | `ClaimEventSqsConsumer` — closes the CQRS loop: receives outbox-relayed events, resolves the claim, auto-refreshes the OpenSearch projection. Runs on a dedicated `SmartLifecycle` thread (a 10 s long-poll inside `@Scheduled` would starve the shared scheduler running the outbox relay) |
| **Poison vs transient handling** | Unparseable payloads / missing claims → deleted (logged; DLQ in prod). Transient failures (OpenSearch down) → left for visibility-timeout redelivery, preserving per-claim FIFO order |
| **Reconciliation job** | `SearchReconciliationJob` — 6-hour drift-repair sweep re-projecting claims updated in a 7-hour lookback (overlap ensures no gap); idempotent, continues past individual failures |
| **Customer360 (FR-05)** | New `customer` bounded context: `GET /api/v1/customers/{policyNumber}/view` — claim counts by status, financial totals, fraud exposure, recent activity. Aggregated from **MySQL** (money totals must be strongly consistent), not the eventually-consistent read model |
| **WebSocket dashboard (FR-06)** | STOMP at `/ws`, metrics on `/topic/metrics` — pushed on every consumed claim event + a 10 s heartbeat. REST fallback `GET /api/v1/dashboard/metrics` |
| **Scheduling hygiene** | `SchedulingConfig` centralizes `@EnableScheduling` (was riding on `OutboxRelayScheduler`); scheduler pool sized to 4 so the four background jobs never serialize behind each other |
| **Tests** | 52 passing (+15 new) — consumer poison/transient paths, reconciliation sweep resilience, Customer360 aggregation, dashboard metrics. Still zero real AWS, zero Docker |

**Week 3 patterns added:** SQS long-poll consumer on dedicated lifecycle thread · Poison-message vs transient-failure discrimination · Scheduled drift reconciliation · Consistency-driven model selection (MySQL for money, OpenSearch for search) · STOMP WebSocket broadcast

**Deferred:** Testcontainers MySQL + LocalStack integration tests (blocked on Docker Desktop install)

### ✅ Week 4 — Documents + Notifications + PII Encryption + WS Auth + CI (commit `498a095`)

| Area | Delivered |
|---|---|
| **Documents BC (FR-07)** | New `document` bounded context: `ClaimAttachment` entity (`claim_documents`, Flyway V4). Presigned-PUT protocol — register → client PUTs directly to S3 → confirm → OCR. **File bytes never transit the API tier** |
| **OCR** | `OcrEngine` port → Textract `DetectDocumentText` (real) / stub (test). OCR failure marks `OCR_FAILED` (re-triggerable), never a 500 — extraction is enrichment, not a gate |
| **Notification engine (FR-08)** | `Notification` rows (Flyway V5) written **inside the WorkflowService transaction** (outbox principle); scheduled dispatcher delivers via `NotificationSender` port → SNS topic fanout with `channel` message attributes (EMAIL/SMS/IN_APP subscribers filter) |
| **Retry + dead-letter** | Delivery failure increments `retryCount`; at 3 attempts the row is dead-lettered (`DEAD` status — in-table DLQ, queryable for a support dashboard). One failure never blocks the batch |
| **PII encryption** | `AesGcmStringCryptoConverter` — AES-256-GCM JPA converter, random IV per write (`base64(iv‖ciphertext‖tag)`). Optional claimant SSN (Flyway V6) encrypted at rest, **never** in responses or logs. Key from config; Secrets Manager in prod |
| **WebSocket auth** | `JwtStompChannelInterceptor` validates the bearer token on the **STOMP CONNECT frame** (same `JwtDecoder` as REST). Handshake stays open — browsers can't set headers on the WS upgrade — closing the Week 3 gap the right way |
| **CI** | GitHub Actions (`.github/workflows/ci.yml`) — repo hosts on GitHub, superseding the GitLab CI note. `mvn verify` + JaCoCo **40% instruction-coverage gate** (ratchets up when integration tests land) + coverage artifact upload |
| **Tests** | 74 passing (+22 new) — converter roundtrip/IV-uniqueness/wrong-key rejection, document lifecycle + OCR failure, notification fanout/retry/dead-letter, STOMP CONNECT auth. Zero AWS, zero Docker |

**Week 4 patterns added:** Presigned direct-to-storage upload · Ports & adapters for S3/Textract/SNS · Outbox-principle notifications · In-table dead-letter · Envelope-free field encryption (AES-GCM + random IV) · STOMP frame-level auth

**Deferred:** Testcontainers ITs (Docker), `policies`/`customers` tables, Cognito issuer swap, RBAC roles

---

## Project Structure

```
claimsflow360/
├── pom.xml                             # AWS SDK v2 BOM + SQS/Bedrock/OpenSearch/S3/Textract/SNS
├── docker-compose.yml                  # MySQL 8 for local dev (tests use H2)
├── .github/workflows/ci.yml            # GitHub Actions: verify + JaCoCo gate [W4]
└── src/
    ├── main/
    │   ├── java/com/claimsflow/
    │   │   ├── ClaimsFlow360Application.java
    │   │   ├── claims/
    │   │   │   ├── domain/
    │   │   │   │   ├── Claim.java                       # aggregate root + state machine
    │   │   │   │   ├── ClaimStatus.java                 # enum + allowed-transition map
    │   │   │   │   ├── ClaimEvent.java                  # immutable audit event (append-only)
    │   │   │   │   ├── ai/
    │   │   │   │   │   ├── AiSummary.java               # Bedrock summary entity  [W2]
    │   │   │   │   │   └── AiSummaryRepository.java     #                         [W2]
    │   │   │   │   └── fraud/
    │   │   │   │       ├── FraudIndicator.java           # Chain of Responsibility port
    │   │   │   │       ├── FraudIndicatorResult.java     # record
    │   │   │   │       ├── FraudScoreResult.java         # record
    │   │   │   │       ├── FraudScoringChain.java        # aggregates all indicators
    │   │   │   │       ├── AmountThresholdIndicator.java
    │   │   │   │       ├── DuplicateClaimIndicator.java
    │   │   │   │       └── TimingPatternIndicator.java
    │   │   │   ├── application/
    │   │   │   │   ├── ClaimIngestionService.java        # FR-01
    │   │   │   │   ├── WorkflowService.java              # FR-02
    │   │   │   │   ├── ClaimQueryService.java            # MySQL point-reads + OS search
    │   │   │   │   └── AiSummarizationService.java       # FR-03            [W2]
    │   │   │   ├── infra/
    │   │   │   │   ├── persistence/
    │   │   │   │   │   ├── ClaimRepository.java
    │   │   │   │   │   └── ClaimEventRepository.java
    │   │   │   │   ├── messaging/
    │   │   │   │   │   ├── ClaimEventPublisher.java      # interface          [W2]
    │   │   │   │   │   ├── LoggingClaimEventPublisher.java  # @Profile(test)  [W2]
    │   │   │   │   │   └── ClaimEventSqsConsumer.java    # long-poll consumer [W3]
    │   │   │   │   ├── outbox/                           # Transactional Outbox [W2]
    │   │   │   │   │   ├── OutboxEvent.java
    │   │   │   │   │   ├── OutboxStatus.java
    │   │   │   │   │   ├── OutboxEventRepository.java
    │   │   │   │   │   ├── OutboxClaimEventPublisher.java  # @Profile(!test)
    │   │   │   │   │   └── OutboxRelayScheduler.java       # polls + SQS relay
    │   │   │   │   ├── search/                           # OpenSearch CQRS   [W2]
    │   │   │   │   │   ├── ClaimDocument.java            # index document
    │   │   │   │   │   ├── ClaimSearchRequest.java
    │   │   │   │   │   ├── ClaimSearchResult.java
    │   │   │   │   │   ├── ClaimSearchRepository.java    # interface
    │   │   │   │   │   ├── OpenSearchClaimSearchRepository.java  # @Profile(!test)
    │   │   │   │   │   ├── NoOpClaimSearchRepository.java        # @Profile(test)
    │   │   │   │   │   ├── ClaimProjectionService.java   # MySQL → OpenSearch
    │   │   │   │   │   └── SearchReconciliationJob.java  # 6h drift repair    [W3]
    │   │   │   │   └── ai/                              # Bedrock            [W2]
    │   │   │   │       ├── ClaimsSummarizer.java         # interface
    │   │   │   │       ├── BedrockClaimsSummarizer.java  # @Profile(!test) Converse API
    │   │   │   │       ├── StubClaimsSummarizer.java     # @Profile(test)
    │   │   │   │       └── ClaimSummarizationPromptBuilder.java
    │   │   │   └── api/
    │   │   │       ├── ClaimController.java              # 7 endpoints (W1: 4, W2: +3)
    │   │   │       └── dto/
    │   │   │           ├── CreateClaimRequest.java
    │   │   │           ├── TransitionRequest.java
    │   │   │           ├── ClaimResponse.java
    │   │   │           ├── ClaimEventResponse.java
    │   │   │           └── AiSummaryResponse.java        #                   [W2]
    │   │   ├── customer/                                # Customer360 BC     [W3]
    │   │   │   ├── domain/Customer360View.java           # aggregated view record
    │   │   │   ├── application/Customer360Service.java   # FR-05
    │   │   │   └── api/Customer360Controller.java
    │   │   ├── dashboard/                               # Real-time dashboard [W3]
    │   │   │   ├── WebSocketConfig.java                  # STOMP /ws, /topic broker
    │   │   │   ├── ClaimDashboardMetrics.java            # metrics record
    │   │   │   ├── DashboardMetricsService.java          # FR-06
    │   │   │   ├── DashboardBroadcaster.java             # push to /topic/metrics
    │   │   │   ├── DashboardScheduler.java               # 10s heartbeat
    │   │   │   ├── DashboardController.java              # REST fallback
    │   │   │   └── JwtStompChannelInterceptor.java       # CONNECT auth      [W4]
    │   │   ├── document/                                # Documents BC (FR-07) [W4]
    │   │   │   ├── domain/
    │   │   │   │   ├── ClaimAttachment.java              # claim_documents entity
    │   │   │   │   ├── AttachmentStatus.java             # upload/OCR lifecycle
    │   │   │   │   └── ClaimAttachmentRepository.java
    │   │   │   ├── application/DocumentService.java      # register→confirm→OCR
    │   │   │   ├── infra/
    │   │   │   │   ├── DocumentStorage.java              # port
    │   │   │   │   ├── S3DocumentStorage.java            # presigned PUT (!test)
    │   │   │   │   ├── StubDocumentStorage.java          # (test)
    │   │   │   │   ├── OcrEngine.java                    # port
    │   │   │   │   ├── TextractOcrEngine.java            # DetectDocumentText (!test)
    │   │   │   │   └── StubOcrEngine.java                # (test)
    │   │   │   └── api/DocumentController.java
    │   │   ├── notification/                            # Notifications (FR-08) [W4]
    │   │   │   ├── domain/
    │   │   │   │   ├── Notification.java                 # notifications entity
    │   │   │   │   ├── NotificationChannel.java          # EMAIL | SMS | IN_APP
    │   │   │   │   ├── NotificationStatus.java           # PENDING|SENT|FAILED|DEAD
    │   │   │   │   └── NotificationRepository.java
    │   │   │   ├── application/
    │   │   │   │   ├── NotificationService.java          # fanout on status change
    │   │   │   │   └── NotificationDispatcher.java       # 5s delivery + retry/DLQ
    │   │   │   └── infra/
    │   │   │       ├── NotificationSender.java           # port
    │   │   │       ├── SnsNotificationSender.java        # SNS topic (!test)
    │   │   │       └── LoggingNotificationSender.java    # (test)
    │   │   └── shared/
    │   │       ├── config/
    │   │       │   ├── SecurityConfig.java
    │   │       │   ├── SchedulingConfig.java             # central @EnableScheduling [W3]
    │   │       │   └── aws/                             #                    [W2]
    │   │       │       ├── SqsConfig.java               # @Profile(!test)
    │   │       │       ├── BedrockConfig.java           # @Profile(!test)
    │   │       │       ├── OpenSearchConfig.java        # @Profile(!test)
    │   │       │       ├── S3Config.java                # presigner          [W4]
    │   │       │       ├── TextractConfig.java          #                    [W4]
    │   │       │       └── SnsConfig.java               #                    [W4]
    │   │       ├── security/
    │   │       │   └── AesGcmStringCryptoConverter.java  # PII field crypto  [W4]
    │   │       └── exception/
    │   │           ├── DomainException.java
    │   │           ├── ClaimNotFoundException.java
    │   │           ├── CustomerNotFoundException.java    #                   [W3]
    │   │           ├── DocumentNotFoundException.java    #                   [W4]
    │   │           ├── InvalidClaimTransitionException.java
    │   │           ├── DuplicateClaimException.java
    │   │           └── GlobalExceptionHandler.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__init_schema.sql                  # claims, claim_events, fraud_evaluations
    │           ├── V2__add_outbox_events.sql             #                   [W2]
    │           ├── V3__add_ai_summaries.sql              #                   [W2]
    │           ├── V4__add_claim_documents.sql           #                   [W4]
    │           ├── V5__add_notifications.sql             #                   [W4]
    │           └── V6__add_claimant_ssn.sql              #                   [W4]
    └── test/
        ├── java/com/claimsflow/
        │   ├── ClaimsFlow360ApplicationTests.java        # Spring context load (H2)
        │   └── claims/
        │       ├── domain/
        │       │   ├── ClaimTest.java                    # state machine (11 cases)
        │       │   └── fraud/FraudScoringChainTest.java  # chain + indicators
        │       ├── application/
        │       │   ├── ClaimIngestionServiceTest.java
        │       │   ├── WorkflowServiceTest.java
        │       │   └── AiSummarizationServiceTest.java   #                   [W2]
        │       └── infra/
        │           ├── outbox/
        │           │   ├── OutboxClaimEventPublisherTest.java  #             [W2]
        │           │   └── OutboxRelaySchedulerTest.java       #             [W2]
        │           ├── messaging/
        │           │   └── ClaimEventSqsConsumerTest.java      #             [W3]
        │           ├── search/
        │           │   ├── ClaimProjectionServiceTest.java     #             [W2]
        │           │   └── SearchReconciliationJobTest.java    #             [W3]
        │           └── ai/
        │               └── BedrockClaimsSummarizerTest.java    #             [W2]
        ├── java/com/claimsflow/customer/
        │   └── application/Customer360ServiceTest.java  #                    [W3]
        ├── java/com/claimsflow/dashboard/
        │   └── DashboardMetricsServiceTest.java         #                    [W3]
        └── resources/application-test.yml               # H2 + all AWS beans disabled
```

---

## Running Locally

### Prerequisites

| Tool | Status | Notes |
|---|---|---|
| Java 21 | ✅ installed | Oracle JDK at `C:\java21` |
| Maven 3.9+ | ✅ installed | Use PowerShell or a fresh terminal |
| IntelliJ IDEA | ✅ preferred IDE | File → Open → `claimsflow360/` |
| MySQL 8.x | ⚠️ via Docker | `docker compose up -d` (Docker Desktop install pending) |
| Docker Desktop | ⚠️ install pending | Re-run installer as Administrator |
| AWS credentials | ⚠️ needed for W2 real mode | See AWS setup below |
| DBeaver / IntelliJ DB | optional | DB GUI for schema inspection |

### Build & test — no Docker, no AWS needed

```bash
mvn clean verify
```

Runs all **37 tests** against H2 in-memory. All AWS SDK calls are mocked via
Mockito. No MySQL, no Docker, no AWS credentials required.

### Run the dev profile (MySQL + real AWS)

```bash
docker compose up -d          # starts MySQL 8 on localhost:3306
mvn spring-boot:run           # starts on http://localhost:8080
```

Flyway auto-applies V1 → V3 migrations on startup.

For Week 2 AWS features (SQS relay + Bedrock + OpenSearch) to work, also
complete the AWS setup below.

### AWS credentials setup (Week 2 features)

```bash
# Option A — environment variables (recommended for local dev)
export AWS_ACCESS_KEY_ID=<your-key>
export AWS_SECRET_ACCESS_KEY=<your-secret>
export AWS_REGION=ap-south-1

# Option B — shared credentials file
mkdir -p ~/.aws
cat > ~/.aws/credentials <<EOF
[default]
aws_access_key_id = <your-key>
aws_secret_access_key = <your-secret>
EOF

cat > ~/.aws/config <<EOF
[default]
region = ap-south-1
EOF
```

Then update `application.yml`:

```yaml
claimsflow:
  aws:
    sqs:
      claims-events-queue-url: https://sqs.ap-south-1.amazonaws.com/<account>/claimsflow-events.fifo
  opensearch:
    endpoint: https://<your-domain>.ap-south-1.es.amazonaws.com
  ai:
    bedrock:
      model-id: anthropic.claude-3-haiku-20240307-v1:0   # must be enabled in Bedrock console
```

### Running from IntelliJ

1. **File → Open** → select the `claimsflow360/` folder
2. Maven auto-imports (may take 2–3 min first time — downloads AWS SDK + OpenSearch deps)
3. Run `ClaimsFlow360Application` (green ▶)
4. Run all tests: right-click `src/test/java` → **Run 'All Tests'**

---

## API Reference

All endpoints require `Authorization: Bearer <JWT>` (HS256, signed with the
secret in `application.yml`). Generate a test token at [jwt.io](https://jwt.io).

### Week 1 endpoints

```bash
# Submit a new claim
curl -X POST http://localhost:8080/api/v1/claims \
  -H "Authorization: Bearer <JWT>" -H "Content-Type: application/json" \
  -d '{"policyNumber":"POL-001","claimantName":"Jane Doe","amountClaimed":1500.00,"description":"Rear-end collision"}'

# Get claim by reference
curl http://localhost:8080/api/v1/claims/CLM-XXXX \
  -H "Authorization: Bearer <JWT>"

# Get full audit history
curl http://localhost:8080/api/v1/claims/CLM-XXXX/history \
  -H "Authorization: Bearer <JWT>"

# Transition claim state
curl -X POST http://localhost:8080/api/v1/claims/CLM-XXXX/transitions \
  -H "Authorization: Bearer <JWT>" -H "Content-Type: application/json" \
  -d '{"target":"UNDER_REVIEW","reason":"Assigned to adjuster"}'

# Approve with settlement amount
curl -X POST http://localhost:8080/api/v1/claims/CLM-XXXX/transitions \
  -H "Authorization: Bearer <JWT>" -H "Content-Type: application/json" \
  -d '{"target":"APPROVED","approvedAmount":1200.00}'
```

### Week 2 endpoints

```bash
# Full-text + faceted search (OpenSearch read model — eventually consistent)
curl "http://localhost:8080/api/v1/claims?q=fender+bender&status=UNDER_REVIEW&fraudOnly=false&page=0&size=20" \
  -H "Authorization: Bearer <JWT>"

# Search fraud-flagged claims only
curl "http://localhost:8080/api/v1/claims?fraudOnly=true" \
  -H "Authorization: Bearer <JWT>"

# Trigger Bedrock Claude AI summarization (stores in ai_summaries + refreshes OpenSearch)
curl -X POST http://localhost:8080/api/v1/claims/CLM-XXXX/summarize \
  -H "Authorization: Bearer <JWT>"

# Manual OpenSearch projection refresh (idempotent — for drift repair / replay)
curl -X POST http://localhost:8080/api/v1/claims/CLM-XXXX/project \
  -H "Authorization: Bearer <JWT>"
```

### Week 3 endpoints

```bash
# Customer360 — aggregated policyholder view (MySQL-backed, strongly consistent)
curl http://localhost:8080/api/v1/customers/POL-001/view \
  -H "Authorization: Bearer <JWT>"

# Dashboard metrics — REST fallback for the WebSocket stream
curl http://localhost:8080/api/v1/dashboard/metrics \
  -H "Authorization: Bearer <JWT>"
```

```javascript
// WebSocket live metrics — STOMP client (e.g. @stomp/stompjs)
// Week 4: the CONNECT frame must carry the same JWT as the REST API
const client = new StompJs.Client({
  brokerURL: "ws://localhost:8080/ws",
  connectHeaders: { Authorization: "Bearer <JWT>" }
});
client.onConnect = () =>
  client.subscribe("/topic/metrics", msg => console.log(JSON.parse(msg.body)));
client.activate();
// Pushed on every consumed claim event + a 10s heartbeat
```

### Week 4 endpoints

```bash
# 1. Register a document — returns a presigned S3 PUT URL
curl -X POST http://localhost:8080/api/v1/claims/CLM-XXXX/documents \
  -H "Authorization: Bearer <JWT>" -H "Content-Type: application/json" \
  -d '{"fileName":"hospital-report.pdf","contentType":"application/pdf","sizeBytes":204800}'

# 2. Upload the file DIRECTLY to S3 (no auth header — the URL is the credential)
curl -X PUT "<uploadUrl-from-step-1>" \
  -H "Content-Type: application/pdf" --data-binary @hospital-report.pdf

# 3. Confirm the upload, then trigger Textract OCR
curl -X POST http://localhost:8080/api/v1/documents/1/confirm -H "Authorization: Bearer <JWT>"
curl -X POST http://localhost:8080/api/v1/documents/1/ocr     -H "Authorization: Bearer <JWT>"

# List a claim's documents (includes extracted OCR text)
curl http://localhost:8080/api/v1/claims/CLM-XXXX/documents -H "Authorization: Bearer <JWT>"

# Submit a claim with PII — SSN is AES-256 encrypted at rest, never echoed back
curl -X POST http://localhost:8080/api/v1/claims \
  -H "Authorization: Bearer <JWT>" -H "Content-Type: application/json" \
  -d '{"policyNumber":"POL-001","claimantName":"Jane Doe","amountClaimed":1500.00,"ssn":"123-45-6789"}'
```

---

## Interview Talking Points

### Week 1

1. **Why modular monolith first?** — Deployment simplicity, strict package boundaries enforce bounded contexts, and it migrates cleanly to microservices if scale demands it. No distributed-transaction overhead.
2. **Why enum state machine over full GoF State pattern?** — Enum + transition map gives identical invariant enforcement and audit characteristics with 1/5 the code. History is captured in the `claim_events` table (Memento equivalent) enabling full replay.
3. **Why Chain of Responsibility for fraud?** — Each indicator is independent and testable in isolation. Spring auto-discovers all `FraudIndicator` beans — adding a 4th or 15th indicator is zero-touch.

### Week 2

4. **Transactional Outbox vs dual-write** — Writing to `outbox_events` in the same `@Transactional` as the business write is the *only* correct way to guarantee event delivery without 2PC. The relay is a separate, idempotent process — SQS failures don't affect the business write.
5. **Why FIFO SQS for the outbox relay?** — `messageGroupId=claimRef` ensures per-claim ordering across the replay window. `messageDeduplicationId=outbox_id` prevents duplicate SQS messages on relay retries.
6. **Profile-based bean switching for AWS** — `@Profile("!test")` on `SqsConfig`, `BedrockConfig`, `OpenSearchConfig` means tests require zero real AWS infrastructure. This is the correct Spring Boot pattern (not `@MockBean` polluting production bean definitions).
7. **OpenSearch Query DSL — never string concat** — `MultiMatchQuery` + `BoolQuery` with `FieldValue` typed parameters is injection-safe and lets OpenSearch optimise the query plan. String concatenation breaks on special characters and is a security risk.
8. **Bedrock Converse API vs InvokeModel** — Converse API is model-agnostic. Swapping Claude Haiku (cost) to Claude Sonnet (accuracy) is a one-line `model-id` config change. InvokeModel requires request/response format changes per model.
9. **Why `temperature=0.2` for claim summarization?** — Low temperature produces deterministic, structured JSON output. High temperature is for creative tasks; for classification/extraction you want consistency.

### Week 3

10. **Why does the SQS consumer run on its own thread, not `@Scheduled`?** — A 10-second long poll inside a scheduled method blocks the shared TaskScheduler; with the default single-thread pool it would starve the outbox relay entirely. `SmartLifecycle` + a dedicated executor gives the consumer its own thread with clean startup/shutdown semantics.
11. **Poison vs transient message handling** — Unparseable payloads and events for missing claims are *permanent* failures: delete them (DLQ in prod) or they block the FIFO message group forever. OpenSearch-down is *transient*: leave the message for visibility-timeout redelivery — the FIFO group blocks briefly, which is exactly what preserves per-claim ordering.
12. **Why is Customer360 built from MySQL and not OpenSearch?** — Money totals shown to a policyholder must be strongly consistent; a 2-second-stale approved amount is a real complaint. A single policyholder has tens of claims — one indexed query — so the relational path is cheap. OpenSearch stays the engine for portfolio-wide search/analytics where staleness is acceptable.
13. **Why does the reconciliation lookback (7 h) exceed the interval (6 h)?** — The overlap guarantees a claim updated moments before a sweep is still covered by the next one. Reconciliation over an idempotent upsert means over-projecting costs nothing; under-projecting is silent drift.
14. **Why centralize `@EnableScheduling`?** — It was riding on `OutboxRelayScheduler`; deleting that one class would have silently disabled every other scheduled job. Cross-cutting switches belong in dedicated config, not on an arbitrary component.

### Week 4

15. **Why presigned URLs instead of proxying uploads?** — A 20 MB accident-photo upload through the API tier consumes a servlet thread, heap, and bandwidth for the whole transfer. Presigned PUT sends bytes directly to S3; the API only ever handles metadata. The URL is scoped to one key, one content type, 15 minutes.
16. **Why is OCR failure not an error response?** — Textract extraction is *enrichment*, not a gate. `OCR_FAILED` is a re-triggerable state on the attachment; the claim proceeds regardless. Same fail-safe stance as the Bedrock fallback.
17. **Why do notification rows commit with the claim transition?** — Same reasoning as the outbox: if the transition commits but the notification enqueue fails (or vice versa), you get silent gaps. One transaction, then async delivery — an SNS outage delays notifications but never blocks or rolls back a claim.
18. **Why an in-table dead-letter instead of an SQS DLQ?** — The dispatcher already owns delivery state in MySQL; `status=DEAD` gives the same semantics (isolate poison, keep evidence, manual replay) plus free queryability for a support dashboard. An SQS DLQ earns its place when delivery moves to a real queue consumer.
19. **Why AES-GCM with a random IV — and what does that cost?** — GCM gives authenticated encryption (tamper = decrypt fails loudly, never garbage). A random IV per write means identical SSNs produce different ciphertexts, so the column leaks nothing — but equality search is impossible *by design*. If lookup-by-SSN is ever needed: separate keyed-HMAC blind-index column, never deterministic encryption.
20. **Why authenticate the STOMP CONNECT frame instead of the WS handshake?** — Browsers cannot set an `Authorization` header on the WebSocket upgrade request. The standard pattern: handshake stays open, the CONNECT frame carries the bearer token as a STOMP native header, and a `ChannelInterceptor` validates it with the same `JwtDecoder` as REST. One token, both transports; no subscriptions possible before auth.
