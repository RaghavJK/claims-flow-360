# ClaimsFlow360 — High-Level Design (HLD)

**Document version:** 2.0  
**Last updated:** 2026-05-09  
**Author:** Raghavendra K Murthy — Senior Principal Architect  
**Status:** Living document — updated at the end of each delivery week  

---

## Table of Contents

1. [System Context](#1-system-context)
2. [Architecture Overview](#2-architecture-overview)
3. [Bounded Contexts](#3-bounded-contexts)
4. [Key Data Flows](#4-key-data-flows)
5. [CQRS Data Model Split](#5-cqrs-data-model-split)
6. [Technology Stack Rationale](#6-technology-stack-rationale)
7. [Non-Functional Requirements](#7-non-functional-requirements)
8. [Deployment Topology](#8-deployment-topology)
9. [Architecture Decision Records (ADRs)](#9-architecture-decision-records)
10. [Security Model](#10-security-model)
11. [Resilience Strategy](#11-resilience-strategy)
12. [Roadmap Traceability](#12-roadmap-traceability)

---

## 1. System Context

### L0 — System Context Diagram

```
                              ┌─────────────────────────────────────────────┐
                              │               EXTERNAL ACTORS                │
                              └─────────────────────────────────────────────┘
         ┌─────────────┐            ┌──────────────┐         ┌──────────────┐
         │  Claimant   │            │   Adjuster / │         │  Partner API │
         │  (Web/App)  │            │  SIU Officer │         │  (B2B REST)  │
         └──────┬──────┘            └──────┬───────┘         └──────┬───────┘
                │  HTTPS / JWT             │  HTTPS / JWT            │  HTTPS / API Key
                └──────────────────────────┼─────────────────────────┘
                                           │
                               ┌───────────▼────────────┐
                               │     ClaimsFlow360       │
                               │   Spring Boot 3.3       │
                               │   REST API :8080        │
                               │  (Modular Monolith)     │
                               └───────────┬────────────┘
                                           │
              ┌────────────────────────────┼──────────────────────────┐
              │                            │                          │
   ┌──────────▼──────────┐    ┌────────────▼───────────┐  ┌──────────▼──────────┐
   │  MySQL 8 (Aurora)   │    │  Amazon SQS FIFO       │  │  AWS OpenSearch      │
   │  Write / Command    │    │  claimsflow-events.fifo │  │  Read / Query model  │
   │  (source of truth)  │    │  (at-least-once relay)  │  │  (claims index)      │
   └─────────────────────┘    └────────────────────────┘  └─────────────────────┘
                                           │
                               ┌───────────▼────────────┐
                               │  AWS Bedrock            │
                               │  Claude Converse API    │
                               │  (AI Summarization)     │
                               └────────────────────────┘
```

### External Actors

| Actor | Interaction | Protocol |
|---|---|---|
| Claimant (web/mobile) | Submit claim, view status | HTTPS + JWT |
| Adjuster / SIU Officer | Review, transition, approve/deny | HTTPS + JWT |
| Partner API (B2B) | Automated claim submission | HTTPS + API Key |
| AWS Bedrock | Generates structured JSON summaries | AWS SDK v2 (Converse API) |
| Amazon SQS FIFO | Receives domain events from outbox relay | AWS SDK v2 |
| AWS OpenSearch | CQRS read model — full-text + faceted search | OpenSearch Java Client |
| MySQL (Aurora) | Authoritative write model | JDBC / Spring Data JPA |

---

## 2. Architecture Overview

### Architecture Style

**Modular Monolith + CQRS** (target: microservices when justified by scale)

```
┌────────────────────────────────────────────────────────────────────────────┐
│                        ClaimsFlow360 Process Boundary                       │
│                                                                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐            │
│  │  Claims BC       │  │  Customer BC     │  │  Documents BC    │  [W3/W4]  │
│  │                  │  │  [Week 3]        │  │  [Week 4]        │            │
│  │  ┌────────────┐  │  │  Customer360     │  │  S3 + Textract   │            │
│  │  │  Domain    │  │  │  PolicyHolder    │  │  OCR pipeline    │            │
│  │  │  Layer     │  │  │  OpenSearch view │  │                  │            │
│  │  └────────────┘  │  └─────────────────┘  └─────────────────┘            │
│  │  ┌────────────┐  │                                                        │
│  │  │Application │  │  ┌─────────────────────────────────────────────────┐  │
│  │  │  Services  │  │  │               Shared Kernel                      │  │
│  │  └────────────┘  │  │  SecurityConfig · GlobalExceptionHandler         │  │
│  │  ┌────────────┐  │  │  AWS Config (SQS, Bedrock, OpenSearch)           │  │
│  │  │   Infra    │  │  │  DomainException hierarchy · Flyway migrations   │  │
│  │  │  Adapters  │  │  └─────────────────────────────────────────────────┘  │
│  │  └────────────┘  │                                                        │
│  └─────────────────┘                                                        │
└────────────────────────────────────────────────────────────────────────────┘
```

### Key Architectural Principles

| Principle | Implementation |
|---|---|
| Domain-Driven Design | Four bounded contexts; Claim is aggregate root with domain invariants |
| CQRS | MySQL = write model; OpenSearch = read model |
| Event-Driven | Transactional Outbox → SQS FIFO → downstream projections |
| Ports & Adapters | Interfaces (`ClaimEventPublisher`, `ClaimSearchRepository`, `ClaimsSummarizer`) with profile-switched adapters |
| Fail-Safe by Default | Resilience4j circuit breaker + retry on all external integrations |
| Profile-Based Isolation | `@Profile("!test")` on all real AWS beans; test profile runs zero real infrastructure |

---

## 3. Bounded Contexts

### Claims Bounded Context (fully implemented — Week 1 & 2)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Claims Bounded Context                                                   │
│                                                                           │
│  Domain Layer                                                             │
│  ├── Claim (aggregate root)       — state machine + invariants           │
│  ├── ClaimStatus (enum)           — transition map (ALLOWED)             │
│  ├── ClaimEvent (append-only)     — immutable audit log                  │
│  ├── AiSummary                    — Bedrock summary persisted entity      │
│  └── Fraud sub-domain             — FraudScoringChain + 3 indicators     │
│                                                                           │
│  Application Layer                                                        │
│  ├── ClaimIngestionService        — FR-01 (submit + fraud + publish)     │
│  ├── WorkflowService              — FR-02 (transitions + approval)       │
│  ├── ClaimQueryService            — point reads (MySQL) + search (OS)    │
│  └── AiSummarizationService       — FR-03 (Bedrock + upsert + project)  │
│                                                                           │
│  Infrastructure Layer (Adapters)                                          │
│  ├── persistence/  ClaimRepository, ClaimEventRepository                 │
│  ├── messaging/    ClaimEventPublisher (interface)                       │
│  │   ├── OutboxClaimEventPublisher    (@Profile !test — real)            │
│  │   └── LoggingClaimEventPublisher   (@Profile test — stub)             │
│  ├── outbox/       OutboxEvent, OutboxRelayScheduler                     │
│  ├── search/       ClaimSearchRepository (interface)                     │
│  │   ├── OpenSearchClaimSearchRepository  (@Profile !test)               │
│  │   └── NoOpClaimSearchRepository        (@Profile test)                │
│  └── ai/           ClaimsSummarizer (interface)                          │
│      ├── BedrockClaimsSummarizer    (@Profile !test)                     │
│      └── StubClaimsSummarizer       (@Profile test)                      │
└─────────────────────────────────────────────────────────────────────────┘
```

### Customer Bounded Context (Week 3 — planned)

Aggregated policyholder view: lifetime claim count, open/closed by year, fraud flag rate, coverage utilization. Backed by OpenSearch faceted aggregations.

### Documents Bounded Context (Week 4 — planned)

S3 upload ingestion → Amazon Textract OCR → structured data extraction → claim enrichment.

### AI Sub-domain (cross-cutting)

Bedrock integration is inside the Claims BC's `infra/ai` package. The `AiSummary` entity lives under `claims/domain/ai` (owned by Claims BC). The Converse API call is protected by Resilience4j circuit breaker.

---

## 4. Key Data Flows

### Flow 1 — Claim Submission (Happy Path)

```
Client
  │
  │  POST /api/v1/claims
  ▼
ClaimController
  │  validates DTO
  ▼
ClaimIngestionService  (@Transactional)
  │
  ├─► Claim.submit()                   ← domain invariants enforced
  ├─► ClaimRepository.save()           ← writes to MySQL claims table
  ├─► FraudScoringChain.score()        ← runs all FraudIndicator beans
  ├─► Claim.assignFraudScore()
  ├─► ClaimEventRepository.save(x2)    ← SUBMITTED + FRAUD_SCORED events
  └─► ClaimEventPublisher.publish(x2)  ─────────────────────────────────────►
                                                                              │
                                                             OutboxClaimEventPublisher
                                                               writes 2 OutboxEvent
                                                               rows to outbox_events
                                                               (same TX — atomic)
  │
  ◄─ Claim returned (HTTP 201)

  (2 seconds later, async)
OutboxRelayScheduler.relay()
  │
  ├─► SELECT pending from outbox_events (findPendingBatch)
  ├─► SqsClient.sendMessage(FIFO, groupId=claimRef, dedupeId=outboxId)
  └─► OutboxEvent.markSent()

  (SQS consumer — Week 3)
  @SqsListener
  └─► ClaimProjectionService.project(claimRef)
       └─► OpenSearch upsert (ClaimDocument)
```

### Flow 2 — AI Summarization

```
Client
  │  POST /api/v1/claims/{ref}/summarize
  ▼
ClaimController
  ▼
AiSummarizationService  (@Transactional)
  │
  ├─► ClaimRepository.findByClaimRef()          ← MySQL point read
  ├─► ClaimsSummarizer.summarize(claim)
  │      │  @CircuitBreaker + @Retry
  │      └─► BedrockRuntimeClient.converse()    ← AWS Bedrock Claude Haiku
  │               ConverseRequest {
  │                 modelId, temperature=0.2,
  │                 prompt: claim fields + fraud score
  │               }
  │               ConverseResponse → JSON string
  │
  ├─► AiSummaryRepository.findByClaimRef()
  │     ├── (None found) → AiSummary.create() + save()
  │     └── (Exists)     → existing.updateSummary() + save()
  │
  └─► ClaimProjectionService.updateSummary()
        └─► OpenSearch upsert (adds aiSummary field)

  ◄─ AiSummaryResponse (HTTP 200)
```

### Flow 3 — Search (CQRS Read Path)

```
Client
  │  GET /api/v1/claims?q=flood&status=UNDER_REVIEW&fraudOnly=false
  ▼
ClaimController
  ▼
ClaimQueryService.search(ClaimSearchRequest)
  ▼
OpenSearchClaimSearchRepository.search()
  │
  ├─► Build MultiMatchQuery {
  │     fields: claimantName^3, policyNumber^2, description, aiSummary
  │     fuzziness: AUTO, type: BestFields
  │   }
  ├─► Build BoolQuery {
  │     must:   [MultiMatchQuery]
  │     filter: [term(status=UNDER_REVIEW)]
  │   }
  ├─► Sort: createdAt DESC
  └─► OpenSearch Java Client search()

  ◄─ ClaimSearchResult { hits: List<ClaimDocument>, total, page, size }

  Note: Eventually consistent. Source of truth for authoritative reads = MySQL
        (ClaimQueryService.getByRef() delegates to ClaimRepository directly).
```

### Flow 4 — State Transition

```
Client
  │  POST /api/v1/claims/{ref}/transitions
  │  Body: { "target": "UNDER_REVIEW", "reason": "Assigned to adjuster" }
  ▼
WorkflowService.transition()  (@Transactional)
  │
  ├─► ClaimRepository.findByClaimRef()         ← MySQL
  ├─► Claim.transitionTo(UNDER_REVIEW)         ← validates ALLOWED map
  │     └── throws InvalidClaimTransitionException if illegal
  ├─► ClaimEventRepository.save(TRANSITIONED)
  └─► ClaimEventPublisher.publish()
        └─► OutboxEvent written (same TX)

  (async via outbox relay → SQS → projection)
```

---

## 5. CQRS Data Model Split

```
┌─────────────────────────────────────────┐    ┌─────────────────────────────────────────┐
│         WRITE MODEL (MySQL)              │    │        READ MODEL (OpenSearch)           │
│         Source of truth                  │    │        Eventually consistent             │
├─────────────────────────────────────────┤    ├─────────────────────────────────────────┤
│  claims                                  │    │  Index: claims                           │
│  ├── id (PK, BIGINT AI)                  │    │  Document ID: claimRef                  │
│  ├── claim_ref (UNIQUE)                  │    │                                          │
│  ├── policy_number                       │    │  {                                       │
│  ├── claimant_name                       │    │    claimRef, policyNumber, claimantName, │
│  ├── amount_claimed / amount_approved    │    │    status, amountClaimed, amountApproved,│
│  ├── status (ENUM string)                │    │    fraudScore, fraudFlagged,             │
│  ├── description                         │    │    description, aiSummary,               │
│  ├── fraud_score                         │    │    createdAt, updatedAt                  │
│  ├── created_at, updated_at              │    │  }                                       │
│  └── version (optimistic lock)           │    │                                          │
│                                          │    │  Boosted fields:                         │
│  claim_events                            │    │    claimantName^3, policyNumber^2        │
│  ├── (append-only audit log)             │    │                                          │
│  └── immutable after write               │    │  Filters:                                │
│                                          │    │    status (keyword), fraudFlagged (bool) │
│  fraud_evaluations                       │    │                                          │
│  outbox_events                           │    │  Sort: createdAt DESC                    │
│  ai_summaries                            │    │                                          │
└─────────────────────────────────────────┘    └─────────────────────────────────────────┘

                    SYNC MECHANISM
          MySQL write ──(same TX)──► outbox_events
                        ──(2s poll)──► SQS FIFO
                        ──(consumer)──► OpenSearch upsert
                        Consistency lag target: < 2 seconds
```

### Which model serves which query

| Query Type | Model | Why |
|---|---|---|
| `GET /claims/{ref}` | MySQL | Authoritative point read; must be consistent |
| `GET /claims/{ref}/history` | MySQL | Audit events — requires strict consistency |
| `GET /claims?q=...` | OpenSearch | Full-text, multi-field, faceted — MySQL cannot do this efficiently |
| Fraud dashboard aggregations | OpenSearch | `terms` + `range` aggregations at scale |
| Customer360 view (Week 3) | OpenSearch | Cross-claim aggregations per policyholder |

---

## 6. Technology Stack Rationale

| Component | Choice | Rationale |
|---|---|---|
| **Java 21** | Java 21 LTS | Records, pattern matching, virtual threads (Loom) available |
| **Spring Boot 3.3** | Spring Boot 3.x | Best-of-class enterprise Java framework; native AOT path for GraalVM |
| **MySQL 8 (Aurora)** | Write model | ACID guarantees for business writes; Flyway schema migration |
| **AWS OpenSearch** | Read model | Native AWS, no Elasticsearch licensing risk; Query DSL compatible |
| **SQS FIFO** | Event relay | Per-claim ordering (messageGroupId); exactly-once deduplication; no broker cluster to manage |
| **AWS Bedrock (Claude Haiku)** | AI summarization | Managed LLM; Converse API = model-agnostic; cost-optimized Haiku default, Sonnet via config |
| **Resilience4j** | Circuit breaker | Native Spring Boot 3 integration; annotation-driven; no XML |
| **Flyway** | Schema migration | Version-controlled SQL; works with any JPA dialect; audit trail |
| **Spring Security 6** | Auth | OAuth2 Resource Server; JWT validation; plugs into AWS Cognito in prod |
| **JUnit 5 + Mockito + AssertJ** | Testing | Modern Java test stack; Mockito for unit isolation; AssertJ for fluent assertions |

---

## 7. Non-Functional Requirements

| Category | Requirement | Target | Implementation |
|---|---|---|---|
| **Performance** | Read API p99 | < 200 ms | OpenSearch (indexed keyword filters + sorted by date) |
| **Performance** | Write API p99 | < 500 ms | MySQL single-table write + outbox row (same TX) |
| **Throughput** | Claim ingestion | 500/min sustained | Horizontal scale via EB Auto Scaling; FIFO SQS handles ordering |
| **Availability** | API uptime | 99.95% | Multi-AZ Aurora; Bedrock fallback (degraded JSON on circuit open) |
| **Resilience** | Bedrock outage | No claim blocking | Circuit breaker returns fallback summary; claim still processed |
| **Resilience** | SQS outage | No data loss | Outbox row stays PENDING; relay retries on 2 s schedule |
| **Consistency** | Write model | Strong (ACID) | MySQL + @Transactional + @Version optimistic lock |
| **Consistency** | Read model | Eventual (< 2 s lag) | Outbox relay + SQS + projection; acceptable for search UI |
| **Security** | Auth | JWT RBAC | Spring Security 6 OAuth2 RS; HS256 local, Cognito in prod |
| **Security** | PII | Field-level encryption | AES-256 for SSN/DOB (Week 4) |
| **Audit** | Compliance | Immutable 7-year log | `claim_events` append-only; no UPDATE/DELETE ever issued |
| **Observability** | Metrics | Prometheus-compatible | Spring Actuator + Micrometer + CloudWatch |

---

## 8. Deployment Topology

### Local Development

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Developer Workstation                                                     │
│                                                                            │
│  ┌────────────────────────┐    ┌─────────────────────────────────────┐   │
│  │  IntelliJ IDEA         │    │  docker-compose                      │   │
│  │  Spring Boot :8080     │    │  MySQL 8 :3306                       │   │
│  │  Profile: dev          │    │  OpenSearch :9200   (future)         │   │
│  └────────────────────────┘    └─────────────────────────────────────┘   │
│                                                                            │
│  AWS SDK → ~/.aws/credentials (DefaultCredentialsProvider)                 │
│  SQS FIFO → real AWS endpoint (ap-south-1)                                 │
│  Bedrock  → real AWS Bedrock  (ap-south-1)                                 │
└──────────────────────────────────────────────────────────────────────────┘
```

### Test Execution

```
mvn clean verify   (Profile: test)
│
├── H2 in-memory (flyway.enabled=false, ddl-auto=create-drop)
├── LoggingClaimEventPublisher   (replaces SQS outbox)
├── NoOpClaimSearchRepository    (replaces OpenSearch)
├── StubClaimsSummarizer         (replaces Bedrock)
└── BedrockRuntimeClient mocked  (Mockito in BedrockClaimsSummarizerTest)

Zero real AWS. Zero Docker. All 37 tests green.
```

### Production Target (AWS)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  AWS ap-south-1                                                           │
│                                                                           │
│  ┌─────────────────────────────────────────────┐                        │
│  │  Elastic Beanstalk / ECS Fargate             │                        │
│  │  ClaimsFlow360 container                     │                        │
│  │  Profile: prod                               │                        │
│  └──────────────┬──────────────────────────────┘                        │
│                 │                                                         │
│    ┌────────────┼──────────────────┬──────────────────┐                 │
│    ▼            ▼                  ▼                  ▼                  │
│  Aurora      SQS FIFO         OpenSearch          Bedrock               │
│  MySQL 8     Multi-AZ         Service             Claude Haiku          │
│  Multi-AZ    (2 AZs)          (2 AZs)             Converse API          │
│                                                                           │
│  AWS Secrets Manager → DB credentials, JWT secret                        │
│  AWS CloudWatch     → logs, metrics, alarms                              │
│  AWS Cognito        → OAuth2 issuer for JWT validation                   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Architecture Decision Records (ADRs)

### ADR-001: Modular Monolith over Microservices

**Status:** Accepted  
**Context:** Week 1 delivery scope. Team of 1–3 engineers. Insurance domain has complex inter-context data requirements (claim + policy + customer frequently joined).  
**Decision:** Single Spring Boot deployable with strict package-level bounded contexts. Module boundaries enforced via package structure and interface ports — not network calls.  
**Consequences:** Simpler deployment, no distributed transaction overhead, single JVM transaction boundary. Migration to microservices: extract a bounded context's package into a new Spring Boot project + replace in-process calls with REST/SQS.

---

### ADR-002: Transactional Outbox over Dual-Write

**Status:** Accepted  
**Context:** Need guaranteed at-least-once event delivery to SQS without 2PC.  
**Decision:** Write `outbox_events` row in same `@Transactional` as business write. Separate relay scheduler reads and forwards to SQS.  
**Alternatives rejected:**
- **Dual-write** (save to DB, then directly call SQS): SQS failure after DB commit = silent event loss.
- **Kafka transactional producer**: No Kafka in scope; SQS is simpler for this workload.
- **CDC (Debezium)**: Correct but operationally heavy; requires Kafka + Debezium connector.  
**Consequences:** At-least-once delivery (SQS consumer must be idempotent). 2-second relay lag is acceptable for search consistency. Multi-instance relay requires SELECT FOR UPDATE or Redis lock (Week 3 concern).

---

### ADR-003: SQS FIFO over Standard Queue

**Status:** Accepted  
**Context:** Multiple claim events (SUBMITTED, FRAUD_SCORED, TRANSITIONED) can fire for the same claim within seconds.  
**Decision:** FIFO queue with `messageGroupId=claimRef` for per-claim ordering; `messageDeduplicationId=outboxId` prevents relay-retry duplicates.  
**Consequences:** Throughput limited to 3,000 messages/s per FIFO queue (300/s per group). Sufficient for 500 claims/min. Upgrade to SNS fanout + multiple SQS if throughput demands grow.

---

### ADR-004: OpenSearch as CQRS Read Model

**Status:** Accepted  
**Context:** Need: full-text claim search, fraud flag filtering, Customer360 aggregations, dashboard metrics. MySQL struggles with all of these at scale.  
**Decision:** Project denormalized `ClaimDocument` to OpenSearch `claims` index. Multi-field boosted search + bool filters.  
**Consequences:** Eventual consistency (< 2 s lag). Drift repair via `ClaimProjectionService.project()` idempotent replay. Production requires explicit index mapping (not auto-create).

---

### ADR-005: Bedrock Converse API over InvokeModel

**Status:** Accepted  
**Context:** AI summarization requires calling Claude. Future may swap Claude Haiku (cost) for Claude Sonnet (accuracy) or other models.  
**Decision:** Converse API — model-agnostic abstraction. Model ID is externalized to `application.yml`.  
**Consequences:** Switching models requires only a config change (`claimsflow.ai.bedrock.model-id`). `temperature=0.2` chosen for deterministic JSON output.

---

### ADR-006: Profile-Based AWS Bean Switching

**Status:** Accepted  
**Context:** AWS beans (SqsClient, BedrockRuntimeClient, OpenSearchClient) require real credentials. Tests must run without AWS.  
**Decision:** All real AWS `@Configuration` classes annotated `@Profile("!test")`. Test-profile provides stub implementations (`LoggingClaimEventPublisher`, `NoOpClaimSearchRepository`, `StubClaimsSummarizer`).  
**Consequences:** 37 tests run with zero real AWS. New AWS integrations follow the same pattern.

---

### ADR-007: Chain of Responsibility for Fraud Scoring

**Status:** Accepted  
**Context:** 15+ fraud indicators planned. Each indicator is independently testable. Enabling/disabling indicators at runtime is a business requirement.  
**Decision:** `FraudScoringChain` receives `List<FraudIndicator>` via Spring auto-injection. Any `@Component` implementing `FraudIndicator` is automatically discovered.  
**Consequences:** Zero-touch extensibility — adding indicator 4–15 requires only a new `@Component`. Exception in one indicator is caught and treated as "clean" (safe degradation).

---

## 10. Security Model

```
┌────────────────────────────────────────────────────────────────────────┐
│  Security Layers                                                          │
├────────────────────────────────────────────────────────────────────────┤
│  Transport        HTTPS / TLS 1.3 (ALB terminates in prod)              │
│  Authentication   JWT (HS256 local dev / RS256 Cognito in prod)         │
│  Authorization    Spring Security 6 OAuth2 Resource Server              │
│                   RBAC roles: ROLE_ADJUSTER, ROLE_ADMIN (Week 4)        │
│  PII Protection   AES-256 field-level encryption for SSN/DOB (Week 4)   │
│  Audit            Immutable claim_events — all state changes recorded   │
│  Secrets          AWS Secrets Manager in prod (not env vars)            │
└────────────────────────────────────────────────────────────────────────┘
```

### JWT Validation (local dev)

HS256 symmetric secret configured in `application.yml`:
```yaml
claimsflow.security.jwt-secret: "dev-secret-change-me..."
```
Generate test tokens at [jwt.io](https://jwt.io) with `HS256`, `sub: adjuster@claimsflow.com`.

### Production

Replace with `spring.security.oauth2.resourceserver.jwt.issuer-uri` pointing at AWS Cognito User Pool.

---

## 11. Resilience Strategy

```
External Integration       Circuit Breaker Config          Fallback
─────────────────────────  ──────────────────────────────  ──────────────────────────────────
AWS Bedrock (Claude)       slidingWindow=20, minCalls=10   fallbackSummary() → degraded JSON
                           failureRate=50%, openWait=30s   Claim still processed normally
                           retry: 3 attempts, 500ms exp    No blocking of ingestion flow

SQS FIFO (relay)           No circuit breaker needed       OutboxEvent stays PENDING
                           (outbox relay is async)         Relay retries every 2 seconds
                           retry: 3 attempts (markFailed)  Dead = retryCount >= 3 (stopped)

MySQL (Aurora)             Multi-AZ failover (< 60s)       Spring transaction rollback
                           Optimistic locking (@Version)   409 Conflict on stale read

OpenSearch                 Eventual consistency model       Empty ClaimSearchResult on error
                           IOException caught + wrapped     RuntimeException → 500 ProblemDetail
```

---

## 12. Roadmap Traceability

| Week | Theme | FR Coverage | Status |
|---|---|---|---|
| Week 1 | Core Domain + REST API | FR-01 (partial), FR-02, FR-04 (partial) | ✅ Done |
| Week 2 | Outbox + SQS + OpenSearch CQRS + Bedrock | FR-01, FR-03, FR-04 (full) | ✅ Done |
| Week 3 | SQS Consumer + Customer360 + WebSocket | FR-05, FR-06 | 🔜 Planned |
| Week 4 | Documents + Notifications + Security | FR-07, FR-08, Security hardening | 🔜 Planned |

### Feature Requirements Matrix

| FR | Description | Implemented | Where |
|---|---|---|---|
| FR-01 | Claims Ingestion | ✅ Full | `ClaimIngestionService` |
| FR-02 | Workflow State Machine | ✅ Full | `WorkflowService`, `ClaimStatus.ALLOWED` |
| FR-03 | AI Summarization | ✅ Full | `AiSummarizationService`, `BedrockClaimsSummarizer` |
| FR-04 | Fraud Detection | ✅ 3 indicators | `FraudScoringChain` + 3 `FraudIndicator` beans |
| FR-05 | Customer360 | 🔜 Week 3 | — |
| FR-06 | Real-Time Dashboard | 🔜 Week 3 | WebSocket endpoint planned |
| FR-07 | Document Management | 🔜 Week 4 | S3 + Textract |
| FR-08 | Notifications | 🔜 Week 4 | SNS + SQS DLQ |
