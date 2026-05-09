# ClaimsFlow360 — Low-Level Design (LLD)

**Document version:** 2.0  
**Last updated:** 2026-05-09  
**Author:** Raghavendra K Murthy — Senior Principal Architect  
**Status:** Living document — updated at the end of each delivery week  

---

## Table of Contents

1. [Domain Model](#1-domain-model)
2. [State Machine](#2-state-machine)
3. [Class Design — Claims Bounded Context](#3-class-design--claims-bounded-context)
4. [Sequence Diagrams](#4-sequence-diagrams)
5. [Database Schema](#5-database-schema)
6. [OpenSearch Index Mapping](#6-opensearch-index-mapping)
7. [API Contract](#7-api-contract)
8. [Fraud Scoring Chain](#8-fraud-scoring-chain)
9. [Outbox Pattern — Implementation Detail](#9-outbox-pattern--implementation-detail)
10. [Bedrock Integration — Implementation Detail](#10-bedrock-integration--implementation-detail)
11. [Profile-Based Bean Wiring](#11-profile-based-bean-wiring)
12. [Exception Hierarchy](#12-exception-hierarchy)
13. [Test Architecture](#13-test-architecture)
14. [Configuration Reference](#14-configuration-reference)

---

## 1. Domain Model

### Aggregate Root: `Claim`

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Claim  (Aggregate Root)                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│  id              : Long           (PK, auto-generated)                    │
│  claimRef        : String         (CLM-{UUID-12}, business key, UNIQUE)  │
│  policyNumber    : String         (FK to policy system — external)        │
│  claimantName    : String                                                 │
│  amountClaimed   : BigDecimal     (precision=15, scale=2, > 0)           │
│  amountApproved  : BigDecimal     (set only on APPROVED transition)       │
│  status          : ClaimStatus    (enum, see state machine below)         │
│  description     : String         (free text, max 2000 chars)             │
│  fraudScore      : Integer        (0-100, set by FraudScoringChain)       │
│  createdAt       : Instant        (UTC, set once on submit)               │
│  updatedAt       : Instant        (UTC, updated on every mutation)        │
│  version         : Long           (@Version — optimistic locking)         │
├─────────────────────────────────────────────────────────────────────────┤
│  + submit(claimRef, policyNumber, claimantName, amountClaimed, desc)      │
│      : Claim  [static factory — validates all invariants]                 │
│  + transitionTo(target: ClaimStatus)                                      │
│      : ClaimStatus  [returns previous status; throws on illegal move]     │
│  + assignFraudScore(score: int)                                           │
│      : void  [validates 0-100]                                            │
│  + approve(amountApproved: BigDecimal)                                    │
│      : void  [only callable when status=APPROVED]                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│  ClaimEvent  (Audit / Event Sourcing lite)    │
├─────────────────────────────────────────────┤
│  id          : Long                          │
│  claimId     : Long  (FK → claims.id)        │
│  eventType   : String  (e.g. CLAIM_SUBMITTED)│
│  actorId     : String  (who triggered it)    │
│  details     : String  (free text / JSON)    │
│  occurredAt  : Instant (UTC)                 │
├─────────────────────────────────────────────┤
│  + submitted(claimId, actorId) : ClaimEvent  │
│  + transitioned(claimId, actorId, from, to,  │
│      reason) : ClaimEvent                    │
│  + fraudScored(claimId, actorId, breakdown)  │
│      : ClaimEvent                            │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│  AiSummary  (AI persistence entity)          │
├─────────────────────────────────────────────┤
│  id              : Long                      │
│  claimId         : Long  (FK → claims.id)    │
│  claimRef        : String  (UNIQUE)          │
│  modelId         : String  (Bedrock modelId) │
│  promptTokens    : Integer                   │
│  completionTokens: Integer                   │
│  summary         : TEXT  (JSON from Claude)  │
│  confidenceNote  : String                    │
│  generatedAt     : Instant                   │
├─────────────────────────────────────────────┤
│  + create(claimId, claimRef, modelId,        │
│      summary) : AiSummary                    │
│  + updateSummary(newSummary)                 │
└─────────────────────────────────────────────┘
```

### Value Objects and Records

```java
// Fraud result records (Java 21)
record FraudIndicatorResult(
    String indicatorName,
    int score,
    boolean triggered,
    String reason
) {
    static FraudIndicatorResult clean(String name) { ... }
    static FraudIndicatorResult triggered(String name, int score, String reason) { ... }
}

record FraudScoreResult(
    int totalScore,
    List<FraudIndicatorResult> indicatorResults
) {
    boolean exceeds(int threshold) { return totalScore >= threshold; }
}
```

---

## 2. State Machine

### State Transition Map

```
                    ┌─────────────────────────────────────────────┐
                    │            Claim Workflow FSM                │
                    └─────────────────────────────────────────────┘

            ┌──────────┐
  submit()  │          │
  ─────────►│ SUBMITTED│
            │          │───────────────────────────────────────────►┐
            └────┬─────┘                                            │
                 │ transition(UNDER_REVIEW)                         │
                 ▼                                                   │
          ┌─────────────┐                                           │
          │ UNDER_REVIEW│───────────────────────────────────────────►┤
          └─────┬───────┘                                           │
                │ transition(ADJUDICATION)                          │ transition(DENIED)
                ▼                                                   │  [from any
          ┌─────────────┐                                           │   non-terminal]
          │ ADJUDICATION│                                           │
          └─────┬───────┘                                           │
                │ transition(APPROVED)                              │
                ▼                                                   ▼
          ┌──────────┐                                     ┌──────────┐
          │ APPROVED │                                     │  DENIED  │
          │(terminal)│                                     │(terminal)│
          └──────────┘                                     └──────────┘
           approve() sets
           amountApproved

Deferred (Week 3+): PAYMENT, APPEAL states
```

### Transition Matrix (source of truth: `ClaimStatus.ALLOWED` map)

| From \ To | SUBMITTED | UNDER_REVIEW | ADJUDICATION | APPROVED | DENIED |
|---|:---:|:---:|:---:|:---:|:---:|
| SUBMITTED | — | ✅ | — | — | ✅ |
| UNDER_REVIEW | — | — | ✅ | — | ✅ |
| ADJUDICATION | — | — | — | ✅ | ✅ |
| APPROVED | — | — | — | — | — |
| DENIED | — | — | — | — | — |

### `ClaimStatus` Implementation

```java
public enum ClaimStatus {
    SUBMITTED, UNDER_REVIEW, ADJUDICATION, APPROVED, DENIED;

    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED = Map.of(
        SUBMITTED,    Set.of(UNDER_REVIEW, DENIED),
        UNDER_REVIEW, Set.of(ADJUDICATION, DENIED),
        ADJUDICATION, Set.of(APPROVED, DENIED),
        APPROVED,     Set.of(),      // terminal
        DENIED,       Set.of()       // terminal
    );

    public boolean canTransitionTo(ClaimStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
```

---

## 3. Class Design — Claims Bounded Context

### Interface Ports (Dependency Inversion)

```
«interface»                         «interface»                     «interface»
ClaimEventPublisher                 ClaimSearchRepository           ClaimsSummarizer
─────────────────────               ─────────────────────           ─────────────────────
+ publish(ClaimEvent)               + upsert(ClaimDocument)         + summarize(Claim)
         ▲                          + search(ClaimSearchRequest)              ▲
         │                                   ▲                               │
         ├── OutboxClaimEventPublisher        ├── OpenSearchClaimSearchRepo   ├── BedrockClaimsSummarizer
         │   @Profile("!test")               │   @Profile("!test")           │   @Profile("!test")
         │                                   │                               │
         └── LoggingClaimEventPublisher       └── NoOpClaimSearchRepository  └── StubClaimsSummarizer
             @Profile("test")                    @Profile("test")                @Profile("test")
```

### Application Services

```
ClaimIngestionService
─────────────────────────────────
- claimRepository        : ClaimRepository
- claimEventRepository   : ClaimEventRepository
- fraudScoringChain      : FraudScoringChain
- eventPublisher         : ClaimEventPublisher  ← interface
- fraudThreshold         : int  (@Value)
─────────────────────────────────
+ ingest(IngestClaimCommand, actorId) : Claim  [@Transactional]
  record IngestClaimCommand(policyNumber, claimantName, amountClaimed, description)

WorkflowService
─────────────────────────────────
- claimRepository        : ClaimRepository
- claimEventRepository   : ClaimEventRepository
- eventPublisher         : ClaimEventPublisher
─────────────────────────────────
+ transition(claimRef, target, actorId, reason) : Claim  [@Transactional]
+ approveWithAmount(claimRef, approvedAmount, actorId) : Claim  [@Transactional]

ClaimQueryService
─────────────────────────────────
- claimRepository        : ClaimRepository
- claimEventRepository   : ClaimEventRepository
- searchRepository       : ClaimSearchRepository  ← interface
─────────────────────────────────
+ getByRef(claimRef) : Claim                      [MySQL — strong consistency]
+ getHistory(claimRef) : List<ClaimEvent>          [MySQL — audit log]
+ search(ClaimSearchRequest) : ClaimSearchResult   [OpenSearch — eventual]

AiSummarizationService
─────────────────────────────────
- claimRepository        : ClaimRepository
- aiSummaryRepository    : AiSummaryRepository
- summarizer             : ClaimsSummarizer  ← interface
- projectionService      : ClaimProjectionService
- modelId                : String  (@Value)
─────────────────────────────────
+ summarize(claimRef) : AiSummary  [@Transactional]
```

### Fraud Scoring Chain

```
FraudScoringChain
─────────────────────────────────────────────────────────────
- indicators : List<FraudIndicator>  (Spring-injected — auto-discovers all beans)
─────────────────────────────────────────────────────────────
+ score(claim: Claim) : FraudScoreResult
  Algorithm:
    totalScore = 0
    for each indicator:
      try:
        result = indicator.evaluate(claim)
        totalScore += result.score()
      catch Exception:
        log.error (treat as clean — safe degradation)
    return FraudScoreResult(min(totalScore, 100), indicatorResults)

«interface» FraudIndicator
+ evaluate(claim: Claim) : FraudIndicatorResult

AmountThresholdIndicator
  @Value("${claimsflow.fraud.amount-high-threshold:50000}")
  → score=30 when amountClaimed > threshold

DuplicateClaimIndicator
  → ClaimRepository.countByPolicyNumberAndCreatedAtAfterAndIdNot()
  → 24-hour window; score=40 if duplicates found

TimingPatternIndicator
  → UTC 00:00–05:00 submissions; score=15
```

### Infrastructure — Outbox

```
OutboxEvent
──────────────────────────────────────────
id            : Long (PK)
aggregateType : String  (e.g. "Claim")
aggregateId   : String  (claimRef)
eventType     : String  (e.g. "CLAIM_SUBMITTED")
payload       : TEXT    (JSON)
status        : OutboxStatus  {PENDING, SENT, FAILED}
createdAt     : Instant
sentAt        : Instant
retryCount    : int  (max 3 before permanent FAILED)
──────────────────────────────────────────
+ pending(aggregateType, aggregateId, eventType, payload) : OutboxEvent
+ markSent()
+ markFailed()  ← increments retryCount
+ resetToPending()

OutboxRelayScheduler  (@Profile("!test"), @EnableScheduling)
──────────────────────────────────────────
- outboxEventRepository : OutboxEventRepository
- sqsClient             : SqsClient
- queueUrl              : String (@Value)
MAX_RETRIES = 3
──────────────────────────────────────────
@Scheduled(fixedDelay=2000ms)
+ relay()  [@Transactional]
  → findPendingBatch() → sendToSqs() → markSent()/markFailed()

@Scheduled(fixedDelay=30000ms)
+ retryFailed()  [@Transactional]
  → findRetryableFailed(MAX_RETRIES) → resetToPending()

SQS SendMessageRequest:
  queueUrl            = claimsflow-events.fifo
  messageBody         = OutboxEvent.payload (JSON)
  messageGroupId      = OutboxEvent.aggregateId (=claimRef)
  messageDeduplicationId = OutboxEvent.id (string)
```

---

## 4. Sequence Diagrams

### SD-01: Submit Claim (POST /api/v1/claims)

```
Client          ClaimController   ClaimIngestionService  ClaimRepository   FraudScoringChain  ClaimEventPublisher  OutboxEventRepository
  │                   │                    │                    │                  │                   │                     │
  │ POST /claims       │                    │                    │                  │                   │                     │
  │──────────────────►│                    │                    │                  │                   │                     │
  │                   │ validate DTO        │                    │                  │                   │                     │
  │                   │────────────────────►                    │                  │                   │                     │
  │                   │                    │ Claim.submit()      │                  │                   │                     │
  │                   │                    │ (validate invariants)│                 │                   │                     │
  │                   │                    │────────────────────►│                  │                   │                     │
  │                   │                    │◄── Claim (saved)    │                  │                   │                     │
  │                   │                    │                     │                  │                   │                     │
  │                   │                    │ fraudScoringChain.score(claim)         │                   │                     │
  │                   │                    │───────────────────────────────────────►│                   │                     │
  │                   │                    │◄─── FraudScoreResult (totalScore, breakdown)               │                     │
  │                   │                    │                     │                  │                   │                     │
  │                   │                    │ claim.assignFraudScore()               │                   │                     │
  │                   │                    │                     │                  │                   │                     │
  │                   │                    │ claimEventRepository.save(SUBMITTED)   │                   │                     │
  │                   │                    │────────────────────►│                  │                   │                     │
  │                   │                    │ claimEventRepository.save(FRAUD_SCORED)│                   │                     │
  │                   │                    │────────────────────►│                  │                   │                     │
  │                   │                    │                     │                  │                   │                     │
  │                   │                    │ eventPublisher.publish(submitted)       │                   │                     │
  │                   │                    │──────────────────────────────────────────────────────────►│                     │
  │                   │                    │                     │                  │   OutboxEvent.pending()              │
  │                   │                    │                     │                  │   (aggregateId=claimRef)             │
  │                   │                    │                     │                  │──────────────────────────────────────►│
  │                   │                    │ eventPublisher.publish(fraudScored)     │                   │                     │
  │                   │                    │──────────────────────────────────────────────────────────►│                     │
  │                   │                    │                     │                  │──────────────────────────────────────►│
  │                   │ ◄─ Claim (HTTP 201)│                     │                  │                   │                     │
  │◄──────────────────│                    │                     │                  │                   │                     │
  │                   │                    │  [COMMIT — all in one @Transactional]  │                   │                     │
  │                   │                    │                     │                  │                   │                     │
  │    (2s later — async, separate TX)     │                     │                  │                   │                     │
  │                   │        OutboxRelayScheduler.relay()       │                  │                   │                     │
  │                   │        ─────────────────────────────────────────────────────────────────────────────────────────────►│
  │                   │        findPendingBatch()                 │                  │                   │                     │
  │                   │        ◄─ [OutboxEvent, OutboxEvent]      │                  │                   │                     │
  │                   │        SqsClient.sendMessage(x2)          │                  │                   │                     │
  │                   │        → event.markSent()                 │                  │                   │                     │
```

---

### SD-02: AI Summarization (POST /api/v1/claims/{ref}/summarize)

```
Client          ClaimController   AiSummarizationService  ClaimRepository  ClaimsSummarizer  AiSummaryRepository  ClaimProjectionService
  │                   │                    │                    │                 │                  │                    │
  │ POST /{ref}/summarize                  │                    │                 │                  │                    │
  │──────────────────►│                    │                    │                 │                  │                    │
  │                   │ service.summarize(claimRef)             │                 │                  │                    │
  │                   │────────────────────►                    │                 │                  │                    │
  │                   │                    │ findByClaimRef()   │                 │                  │                    │
  │                   │                    │───────────────────►│                 │                  │                    │
  │                   │                    │◄─ Claim            │                 │                  │                    │
  │                   │                    │                    │                 │                  │                    │
  │                   │                    │ summarizer.summarize(claim)          │                  │                    │
  │                   │                    │────────────────────────────────────►│                  │                    │
  │                   │                    │  @CircuitBreaker + @Retry            │                  │                    │
  │                   │                    │  BedrockRuntimeClient.converse()     │                  │                    │
  │                   │                    │  ← ConverseResponse (JSON string)    │                  │                    │
  │                   │                    │◄─ summaryJson       │                 │                  │                    │
  │                   │                    │                    │                 │                  │                    │
  │                   │                    │ aiSummaryRepository.findByClaimRef() │                  │                    │
  │                   │                    │──────────────────────────────────────────────────────►│                    │
  │                   │                    │                    │                 │   (None found)   │                    │
  │                   │                    │ AiSummary.create() + save()          │                  │                    │
  │                   │                    │──────────────────────────────────────────────────────►│                    │
  │                   │                    │                    │                 │                  │                    │
  │                   │                    │ projectionService.updateSummary(claimRef, summaryJson)  │                    │
  │                   │                    │────────────────────────────────────────────────────────────────────────────►│
  │                   │                    │                    │                 │  project(claimRef) → OpenSearch upsert│
  │                   │                    │                    │                 │                  │                    │
  │                   │ ◄─ AiSummary (200) │                    │                 │                  │                    │
  │◄──────────────────│                    │                    │                 │                  │                    │
```

---

### SD-03: State Transition (POST /api/v1/claims/{ref}/transitions)

```
Client          ClaimController   WorkflowService   ClaimRepository  ClaimEventRepository  ClaimEventPublisher
  │                   │                 │                   │                  │                   │
  │ POST /{ref}/transitions              │                   │                  │                   │
  │ { target: "UNDER_REVIEW" }          │                   │                  │                   │
  │──────────────────►│                 │                   │                  │                   │
  │                   │ transition(ref, UNDER_REVIEW, actor, reason)           │                   │
  │                   │────────────────►│                   │                  │                   │
  │                   │                 │ findByClaimRef()  │                  │                   │
  │                   │                 │──────────────────►│                  │                   │
  │                   │                 │◄─ Claim           │                  │                   │
  │                   │                 │                   │                  │                   │
  │                   │                 │ claim.transitionTo(UNDER_REVIEW)     │                   │
  │                   │                 │ ── validates ALLOWED map             │                   │
  │                   │                 │ ── throws InvalidClaimTransitionException if illegal     │
  │                   │                 │                   │                  │                   │
  │                   │                 │ claimEventRepository.save(TRANSITIONED)                 │
  │                   │                 │───────────────────────────────────►│                   │
  │                   │                 │                   │                  │                   │
  │                   │                 │ eventPublisher.publish(event)        │                   │
  │                   │                 │────────────────────────────────────────────────────────►│
  │                   │                 │    → OutboxEvent.pending() saved (same TX)              │
  │                   │◄─ updated Claim │                   │                  │                   │
  │◄──────────────────│                 │                   │                  │                   │
```

---

### SD-04: Full-Text Search (GET /api/v1/claims?q=flood)

```
Client          ClaimController   ClaimQueryService   OpenSearchClaimSearchRepository   OpenSearch
  │                   │                 │                         │                           │
  │ GET /claims?q=flood&status=UNDER_REVIEW                       │                           │
  │──────────────────►│                 │                         │                           │
  │                   │ queryService.search(ClaimSearchRequest)   │                           │
  │                   │────────────────►│                         │                           │
  │                   │                 │ searchRepository.search()                           │
  │                   │                 │────────────────────────►│                           │
  │                   │                 │    Build MultiMatchQuery │                           │
  │                   │                 │    {claimantName^3,      │                           │
  │                   │                 │     policyNumber^2,      │                           │
  │                   │                 │     description,         │                           │
  │                   │                 │     aiSummary}           │                           │
  │                   │                 │    + BoolQuery filter     │                           │
  │                   │                 │      {status=UNDER_REVIEW}│                          │
  │                   │                 │    + sort createdAt DESC  │                           │
  │                   │                 │    OpenSearchClient.search()──────────────────────►│
  │                   │                 │◄─ SearchResponse (hits, total)◄───────────────────│
  │                   │                 │◄─ ClaimSearchResult      │                           │
  │                   │◄─ ClaimSearchResult                         │                           │
  │◄──── JSON array   │                 │                         │                           │
```

---

## 5. Database Schema

### V1 — Core Schema (`V1__init_schema.sql`)

```sql
CREATE TABLE claims (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_ref       VARCHAR(32)     NOT NULL UNIQUE,
    policy_number   VARCHAR(64)     NOT NULL,
    claimant_name   VARCHAR(200)    NOT NULL,
    amount_claimed  DECIMAL(15,2)   NOT NULL,
    amount_approved DECIMAL(15,2),
    status          VARCHAR(32)     NOT NULL,         -- ClaimStatus enum
    description     VARCHAR(2000),
    fraud_score     INT,
    created_at      DATETIME(6)     NOT NULL,
    updated_at      DATETIME(6)     NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0  -- optimistic locking
);

CREATE INDEX idx_claims_policy_number  ON claims (policy_number);
CREATE INDEX idx_claims_status         ON claims (status);
CREATE INDEX idx_claims_created_at     ON claims (created_at);

CREATE TABLE claim_events (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id    BIGINT          NOT NULL,
    event_type  VARCHAR(64)     NOT NULL,
    actor_id    VARCHAR(100)    NOT NULL,
    details     TEXT,
    occurred_at DATETIME(6)     NOT NULL,
    CONSTRAINT fk_claim_events_claim FOREIGN KEY (claim_id) REFERENCES claims(id)
);

CREATE INDEX idx_claim_events_claim_id ON claim_events (claim_id);

CREATE TABLE fraud_evaluations (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id        BIGINT          NOT NULL,
    indicator_name  VARCHAR(100)    NOT NULL,
    score           INT             NOT NULL,
    triggered       BOOLEAN         NOT NULL,
    reason          TEXT,
    evaluated_at    DATETIME(6)     NOT NULL,
    CONSTRAINT fk_fraud_claim FOREIGN KEY (claim_id) REFERENCES claims(id)
);
```

### V2 — Outbox Events (`V2__add_outbox_events.sql`)

```sql
CREATE TABLE outbox_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type  VARCHAR(64)     NOT NULL,   -- e.g. "Claim"
    aggregate_id    VARCHAR(64)     NOT NULL,   -- claimRef (for SQS FIFO groupId)
    event_type      VARCHAR(64)     NOT NULL,   -- e.g. "CLAIM_SUBMITTED"
    payload         TEXT            NOT NULL,   -- JSON body sent to SQS
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',  -- PENDING|SENT|FAILED
    created_at      DATETIME(6)     NOT NULL,
    sent_at         DATETIME(6),
    retry_count     INT             NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_status      ON outbox_events (status);
CREATE INDEX idx_outbox_aggregate   ON outbox_events (aggregate_type, aggregate_id);
```

### V3 — AI Summaries (`V3__add_ai_summaries.sql`)

```sql
CREATE TABLE ai_summaries (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id            BIGINT          NOT NULL,
    claim_ref           VARCHAR(32)     NOT NULL UNIQUE,
    model_id            VARCHAR(100)    NOT NULL,
    prompt_tokens       INT,
    completion_tokens   INT,
    summary             TEXT            NOT NULL,   -- Claude JSON output
    confidence_note     VARCHAR(500),
    generated_at        DATETIME(6)     NOT NULL,
    CONSTRAINT fk_ai_summary_claim FOREIGN KEY (claim_id) REFERENCES claims(id)
);
```

### Entity Relationship Diagram

```
claims (1) ─────────────────── (N) claim_events
   │                                    (append-only audit)
   │
   │ (1) ─────────────────── (N) fraud_evaluations
   │                                    (per-indicator breakdown)
   │
   │ (1) ─────────────────── (N) outbox_events
   │                                    (relay queue)
   │
   └── (1) ───────────────── (0..1) ai_summaries
                                        (UNIQUE on claim_ref)
```

---

## 6. OpenSearch Index Mapping

### Index: `claims`

```json
{
  "mappings": {
    "properties": {
      "claimRef":        { "type": "keyword" },
      "policyNumber":    { "type": "keyword" },
      "claimantName":    { "type": "text",    "analyzer": "standard" },
      "status":          { "type": "keyword" },
      "amountClaimed":   { "type": "scaled_float", "scaling_factor": 100 },
      "amountApproved":  { "type": "scaled_float", "scaling_factor": 100 },
      "fraudScore":      { "type": "integer" },
      "fraudFlagged":    { "type": "boolean" },
      "description":     { "type": "text",    "analyzer": "standard" },
      "aiSummary":       { "type": "text",    "analyzer": "standard" },
      "createdAt":       { "type": "date",    "format": "strict_date_optional_time" },
      "updatedAt":       { "type": "date",    "format": "strict_date_optional_time" }
    }
  },
  "settings": {
    "number_of_shards":   2,
    "number_of_replicas": 1
  }
}
```

### Query Pattern: Full-Text + Faceted Filter

```json
{
  "query": {
    "bool": {
      "must": [
        {
          "multi_match": {
            "query": "flood claim",
            "fields": ["claimantName^3", "policyNumber^2", "description", "aiSummary"],
            "type":  "best_fields",
            "fuzziness": "AUTO"
          }
        }
      ],
      "filter": [
        { "term": { "status": "UNDER_REVIEW" } },
        { "term": { "fraudFlagged": true } }
      ]
    }
  },
  "sort": [{ "createdAt": { "order": "desc" } }],
  "from": 0,
  "size": 20
}
```

### CQRS Consistency Notes

| Scenario | Source | Staleness |
|---|---|---|
| `GET /claims/{ref}` — view a specific claim | MySQL | Strongly consistent |
| `GET /claims/{ref}/history` — audit trail | MySQL | Strongly consistent |
| `GET /claims?q=...` — search | OpenSearch | Eventually consistent (< 2 s lag) |
| Dashboard aggregations (Week 3) | OpenSearch | Eventually consistent |
| Drift repair | `POST /{ref}/project` | Idempotent MySQL→OS projection replay |

---

## 7. API Contract

### Base URL: `http://localhost:8080/api/v1`

### Authentication

All endpoints require `Authorization: Bearer <JWT>`.  
JWT signed with HS256, secret from `claimsflow.security.jwt-secret`.

### Error Response (RFC-7807 ProblemDetail)

```json
{
  "type":     "about:blank",
  "title":    "Claim Not Found",
  "status":   404,
  "detail":   "No claim with ref CLM-XXXX",
  "instance": "/api/v1/claims/CLM-XXXX"
}
```

### Endpoints

#### `POST /claims` — Submit a new claim

Request:
```json
{
  "policyNumber":  "POL-001",
  "claimantName":  "Jane Doe",
  "amountClaimed": 1500.00,
  "description":   "Rear-end collision on highway"
}
```

Response `201 Created`:
```json
{
  "claimRef":      "CLM-3F7A2B1C9E4D",
  "policyNumber":  "POL-001",
  "claimantName":  "Jane Doe",
  "status":        "SUBMITTED",
  "amountClaimed": 1500.00,
  "fraudScore":    15,
  "createdAt":     "2026-05-09T10:30:00Z"
}
```

Errors: `400` (validation), `409` (duplicate claimRef — extremely rare UUID collision)

---

#### `GET /claims/{ref}` — Get claim by reference

Response `200 OK`: Full `ClaimResponse` (same shape as POST response + `amountApproved`, `description`, `version`).  
Errors: `404` ClaimNotFoundException → ProblemDetail

---

#### `GET /claims/{ref}/history` — Audit event log

Response `200 OK`:
```json
[
  { "eventType": "CLAIM_SUBMITTED",  "actorId": "user@co.com", "details": "...", "occurredAt": "..." },
  { "eventType": "FRAUD_SCORED",     "actorId": "user@co.com", "details": "total=15 ...",      "occurredAt": "..." },
  { "eventType": "CLAIM_TRANSITIONED","actorId": "adjuster@co","details": "SUBMITTED→UNDER_REVIEW","occurredAt": "..." }
]
```

---

#### `POST /claims/{ref}/transitions` — Transition workflow state

Request:
```json
{ "target": "UNDER_REVIEW", "reason": "Assigned to adjuster", "approvedAmount": null }
```

For approval:
```json
{ "target": "APPROVED", "approvedAmount": 1200.00 }
```

Response `200 OK`: Updated `ClaimResponse`  
Errors: `404`, `409` (InvalidClaimTransitionException), `409` (OptimisticLockingFailureException)

---

#### `GET /claims` — Full-text + faceted search (OpenSearch)

Query params:

| Param | Type | Default | Description |
|---|---|---|---|
| `q` | String | — | Full-text query (claimantName, policyNumber, description, aiSummary) |
| `status` | ClaimStatus | — | Filter by status (keyword exact match) |
| `fraudOnly` | boolean | false | Filter to fraud-flagged claims only |
| `page` | int | 0 | Zero-based page index |
| `size` | int | 20 | Page size (max 100) |

Response `200 OK`:
```json
{
  "hits":  [ { ...ClaimDocument... } ],
  "total": 47,
  "page":  0,
  "size":  20
}
```

Note: Eventually consistent — results may lag up to 2 seconds behind MySQL write model.

---

#### `POST /claims/{ref}/summarize` — Trigger Bedrock AI summary

Calls Bedrock Claude Haiku (Converse API), upserts `ai_summaries`, refreshes OpenSearch projection.

Response `200 OK`:
```json
{
  "claimRef":   "CLM-XXXX",
  "modelId":    "anthropic.claude-3-haiku-20240307-v1:0",
  "summary":    "{\"keyFacts\":\"...\",\"coverageMatch\":\"...\",\"redFlags\":\"...\",\"recommendedAction\":\"AUTO_APPROVE\"}",
  "generatedAt":"2026-05-09T10:31:00Z"
}
```

Errors: `404`, `503` (Bedrock circuit open — fallback summary returned instead of error)

---

#### `POST /claims/{ref}/project` — Manual OpenSearch projection refresh

Idempotent. Fetches from MySQL, rebuilds `ClaimDocument`, upserts to OpenSearch.  
Use for drift repair when MySQL and OpenSearch are out of sync.  
Response `204 No Content`.

---

## 8. Fraud Scoring Chain

### Current Indicators (Week 2)

| Indicator | Trigger Condition | Score | Config Key |
|---|---|---|---|
| `AmountThresholdIndicator` | `amountClaimed > ${threshold}` | 30 | `claimsflow.fraud.amount-high-threshold` (default: 50,000) |
| `DuplicateClaimIndicator` | Same policyNumber, another claim in last 24 hours | 40 | Hard-coded window |
| `TimingPatternIndicator` | Submission time UTC 00:00–05:00 | 15 | Hard-coded window |

### Scoring Rules

- Total score = sum of triggered indicator scores, capped at 100.
- Fraud flagged = `totalScore >= ${claimsflow.fraud.threshold}` (default: 70).
- Example: Amount (30) + Duplicate (40) = 70 → **fraud-flagged**.
- Indicators are independent — exception in one does not stop others.
- Adding a new indicator: implement `FraudIndicator`, annotate `@Component`. Zero other changes needed.

### Planned Indicators (Week 3+)

```
HighVelocityPolicyIndicator   — >3 claims in 30 days (score 25)
NewPolicyIndicator            — policy age < 30 days (score 20)
SuspiciousDescriptionIndicator— keyword match on common fraud phrases (score 15)
GeolocationAnomalyIndicator   — claim location vs policy holder address (score 20)
ClaimAmountRoundingIndicator  — amount ends in 000 (score 10)
```

---

## 9. Outbox Pattern — Implementation Detail

### Write Path (within business @Transactional)

```
@Transactional  (ClaimIngestionService.ingest)
   │
   ├── INSERT INTO claims (...)                 ← business write
   ├── INSERT INTO claim_events (SUBMITTED)     ← audit
   ├── INSERT INTO claim_events (FRAUD_SCORED)  ← audit
   ├── INSERT INTO outbox_events (PENDING)      ← event #1 payload
   └── INSERT INTO outbox_events (PENDING)      ← event #2 payload
   
   COMMIT ← all or nothing; no partial state
```

### Relay Path (separate @Transactional, every 2 seconds)

```
@Scheduled(fixedDelay=2000)
@Transactional
   │
   ├── SELECT * FROM outbox_events WHERE status='PENDING' LIMIT 50
   │
   ├── FOR EACH event:
   │     SqsClient.sendMessage(
   │       queueUrl=claimsflow-events.fifo,
   │       messageBody=event.payload,
   │       messageGroupId=event.aggregateId,       ← per-claim FIFO ordering
   │       messageDeduplicationId=event.id          ← SQS dedup (5-min window)
   │     )
   │     SUCCESS → event.markSent() (UPDATE status='SENT', sentAt=now)
   │     FAILURE → event.markFailed() (UPDATE status='FAILED', retryCount++)
   │
   └── COMMIT
```

### Retry Path (every 30 seconds)

```
@Scheduled(fixedDelay=30000)
@Transactional
   │
   ├── SELECT * FROM outbox_events WHERE status='FAILED' AND retry_count < 3
   └── FOR EACH → event.resetToPending() (UPDATE status='PENDING')
       ← next relay tick will pick these up
```

### Failure Modes

| Scenario | Outcome |
|---|---|
| SQS temporarily unavailable | Event stays PENDING, relay retries every 2 s |
| SQS fails 3 relay attempts | Event marked FAILED permanently (DLQ pattern — manual inspection) |
| MySQL unavailable during relay | Spring TX rollback; event stays PENDING (was never marked SENT) |
| Application crash mid-relay | Dirty PENDING rows re-processed on restart (idempotent on consumer side) |
| Multi-instance deployment | Risk of duplicate relay — mitigate with SELECT FOR UPDATE or Redis distributed lock (Week 3) |

---

## 10. Bedrock Integration — Implementation Detail

### Prompt Structure (`ClaimSummarizationPromptBuilder`)

```
You are a senior insurance claims analyst. Analyse the following claim and return 
a structured JSON object with these exact keys: keyFacts, coverageMatch, redFlags, 
recommendedAction.

Claim details:
- Claim Reference:  {claimRef}
- Policy Number:    {policyNumber}
- Claimant Name:    {claimantName}
- Amount Claimed:   {amountClaimed}
- Current Status:   {status}
- Description:      {description}
- Fraud Score:      {fraudScore} / 100  (flagged if >= {fraudThreshold})

Return ONLY the JSON object. No preamble. No explanation.
```

### Bedrock SDK Call

```java
ConverseRequest.builder()
    .modelId("anthropic.claude-3-haiku-20240307-v1:0")
    .messages(Message.builder()
        .role(ConversationRole.USER)
        .content(ContentBlock.fromText(prompt))
        .build())
    .inferenceConfig(InferenceConfiguration.builder()
        .maxTokens(500)
        .temperature(0.2f)   // deterministic JSON; not creative writing
        .build())
    .build()
```

### Resilience Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      bedrockSummarizer:
        slidingWindowSize: 20
        minimumNumberOfCalls: 10
        failureRateThreshold: 50       # opens at 50% failure rate
        waitDurationInOpenState: 30s   # stays open 30s before half-open probe
        permittedNumberOfCallsInHalfOpenState: 3
  retry:
    instances:
      bedrockSummarizer:
        maxAttempts: 3
        waitDuration: 500ms
        exponentialBackoffMultiplier: 2  # 500ms → 1s → 2s
```

### Fallback Behaviour

When circuit is open or all retries exhausted:
```json
{
  "keyFacts":            "Summary unavailable",
  "coverageMatch":       "N/A",
  "redFlags":            "N/A",
  "recommendedAction":   "MANUAL_REVIEW"
}
```
Claim processing is **never blocked** by Bedrock failure.

---

## 11. Profile-Based Bean Wiring

### Active Profile Rules

| Profile | Activated by | AWS beans active? |
|---|---|---|
| `dev` (default) | `spring.profiles.default=dev` | YES (uses `~/.aws/credentials`) |
| `test` | `application-test.yml` sets `spring.profiles.active=test` | NO (all stubs) |
| `prod` | EB environment / ECS task env var | YES (IAM role via instance profile) |

### Bean Switching Map

```
Interface                   @Profile("!test")                    @Profile("test")
─────────────────────────   ─────────────────────────────────    ──────────────────────────────
ClaimEventPublisher         OutboxClaimEventPublisher             LoggingClaimEventPublisher
                            (writes to outbox_events table)       (logs at DEBUG level)

ClaimSearchRepository       OpenSearchClaimSearchRepository       NoOpClaimSearchRepository
                            (real OpenSearch Java Client)          (returns empty results)

ClaimsSummarizer            BedrockClaimsSummarizer               StubClaimsSummarizer
                            (calls Bedrock Converse API)           (returns deterministic JSON)

SqsClient                   SqsConfig.sqsClient()                 — (not defined)
                            (DefaultCredentialsProvider)

BedrockRuntimeClient        BedrockConfig.bedrockRuntimeClient()  — (Mockito mock in test)
                            (DefaultCredentialsProvider)

OpenSearchClient            OpenSearchConfig.openSearchClient()   — (not defined)
                            (ApacheHttpClient5TransportBuilder)
```

### Test Profile Config (`application-test.yml`)

```yaml
spring:
  profiles:
    active: test
  datasource:
    url: jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    database-platform: org.hibernate.dialect.H2Dialect
  flyway:
    enabled: false
```

---

## 12. Exception Hierarchy

```
RuntimeException
└── DomainException  (abstract base — all domain errors extend this)
    ├── ClaimNotFoundException         → HTTP 404 ProblemDetail
    ├── InvalidClaimTransitionException → HTTP 409 ProblemDetail
    │     fields: claimRef, from (ClaimStatus), to (ClaimStatus)
    └── DuplicateClaimException        → HTTP 409 ProblemDetail

GlobalExceptionHandler (@RestControllerAdvice)
├── handles DomainException subtypes → ProblemDetail with correct status
├── handles MethodArgumentNotValidException → 400 with field errors
├── handles OptimisticLockingFailureException → 409
└── handles Exception (catch-all) → 500
```

### ProblemDetail Structure (RFC 7807)

```json
{
  "type":     "about:blank",
  "title":    "Invalid Claim Transition",
  "status":   409,
  "detail":   "Claim CLM-XXXX cannot transition from APPROVED to SUBMITTED",
  "instance": "/api/v1/claims/CLM-XXXX/transitions"
}
```

---

## 13. Test Architecture

### Test Coverage (Week 2: 37 tests)

| Test Class | Layer | What It Tests |
|---|---|---|
| `ClaimsFlow360ApplicationTests` | Integration (Spring context) | Context loads with H2 + test profile |
| `ClaimTest` | Domain | 11 state machine cases — legal/illegal transitions, invariant validation |
| `FraudScoringChainTest` | Domain | Chain aggregation, indicator isolation, exception safety |
| `ClaimIngestionServiceTest` | Application | Full ingestion flow with mocked repos + publisher |
| `WorkflowServiceTest` | Application | Transition + approve; not-found + illegal-transition throw |
| `AiSummarizationServiceTest` | Application | Create new summary, update existing, not-found throw |
| `OutboxClaimEventPublisherTest` | Infrastructure | Outbox row fields correct, JSON payload valid |
| `OutboxRelaySchedulerTest` | Infrastructure | No-op on empty, SQS send args, markSent/markFailed, retry reset |
| `ClaimProjectionServiceTest` | Infrastructure | Document field mapping, fraud flag threshold, not-found throw |
| `BedrockClaimsSummarizerTest` | Infrastructure | Converse API called with correct model + prompt content |

### Testing Principles

- **No real AWS**: all AWS SDK clients mocked via Mockito (`@Mock BedrockRuntimeClient`)
- **No Docker**: H2 in-memory (`MODE=MySQL`) with `ddl-auto=create-drop`
- **No Spring context in unit tests**: `@ExtendWith(MockitoExtension.class)` only
- **Spring context only** in `ClaimsFlow360ApplicationTests` (smoke test)
- **AssertJ** for fluent, readable assertions
- **ArgumentCaptor** for verifying infrastructure calls (e.g. `ClaimDocument` passed to `searchRepository.upsert()`)

### Test Dependency Injection Pattern

```java
@ExtendWith(MockitoExtension.class)
class AiSummarizationServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock AiSummaryRepository aiSummaryRepository;
    @Mock ClaimsSummarizer summarizer;          // interface — no Bedrock needed
    @Mock ClaimProjectionService projectionService;

    AiSummarizationService service;

    @BeforeEach
    void setUp() {
        // Constructor injection — no Spring context
        service = new AiSummarizationService(
            claimRepository, aiSummaryRepository, summarizer, projectionService,
            "anthropic.claude-3-haiku-20240307-v1:0");
    }
}
```

---

## 14. Configuration Reference

### `application.yml` — Key Properties

| Property | Default | Description |
|---|---|---|
| `claimsflow.fraud.threshold` | `70` | Fraud flag threshold (0-100) |
| `claimsflow.fraud.amount-high-threshold` | `50000` | AmountThresholdIndicator trigger value |
| `claimsflow.aws.region` | `ap-south-1` | AWS SDK region for all clients |
| `claimsflow.aws.sqs.claims-events-queue-url` | — | SQS FIFO URL for outbox relay |
| `claimsflow.opensearch.endpoint` | `http://localhost:9200` | OpenSearch cluster endpoint |
| `claimsflow.ai.bedrock.model-id` | `anthropic.claude-3-haiku-20240307-v1:0` | Bedrock model (swap to Sonnet via config) |
| `claimsflow.ai.bedrock.max-tokens` | `500` | Max output tokens per Bedrock call |
| `claimsflow.outbox.relay-interval-ms` | `2000` | Outbox relay polling interval |
| `claimsflow.outbox.retry-interval-ms` | `30000` | Failed event retry interval |
| `claimsflow.security.dev-mode` | `true` | Enables HS256 JWT for local dev |
| `claimsflow.security.jwt-secret` | `dev-secret-...` | HS256 signing key (min 256 bits) |

### Resilience4j Tuning Guide

| Parameter | Current | Tighten If | Loosen If |
|---|---|---|---|
| `slidingWindowSize` | 20 | High Bedrock traffic (use 50+) | Low traffic (use 10) |
| `failureRateThreshold` | 50% | Want earlier circuit open | Bedrock is flaky (use 70%) |
| `waitDurationInOpenState` | 30s | Bedrock recovers quickly | Bedrock is slow to recover |
| `maxAttempts` | 3 | Too many retries under load | Bedrock transient errors |
| `waitDuration` | 500ms (exp) | Reduce retry pressure | Bedrock needs more time |
