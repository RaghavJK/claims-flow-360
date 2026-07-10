# ClaimsFlow360 — Developer Log & Engineering Stories

> **Format:** Each week is written as a self-contained Medium / LinkedIn post.  
> Copy any week's section as-is into your publishing platform.  
> Tags are listed at the end of each post for discoverability.

---

## Week 1 — Building an Insurance Claims Engine from Scratch

### *How I modelled a real-world insurance workflow using DDD, a state machine, and a fraud scoring chain in Java 21 + Spring Boot*

---

Every engineer says they do "enterprise Java." Not many can show it.

I started building **ClaimsFlow360** this week — an end-to-end insurance claims processing platform that mirrors exactly what I've architected at scale for BFSI clients. No toy examples, no CRUD-only demos. Real patterns: Domain-Driven Design, CQRS, event sourcing, fraud detection, AI augmentation.

Week 1 goal: get the core domain working, fraud scoring live, a production-quality REST API running, and all of it covered with unit tests. No shortcuts.

Here's what I learned, what I built, and the one bug that took me longer to fix than I care to admit.

---

### The Domain Model: Aggregate Root with Built-in Invariants

The first thing I do with any bounded context is ask: *what is the aggregate root?*

In insurance claims, the answer is obvious — **Claim**. Everything in the Claims context lives and dies by the Claim entity. It owns the workflow state, it enforces invariants, and nothing outside should be able to put a claim into an invalid state.

I used a static factory method instead of a public constructor. This is non-negotiable for aggregate roots:

```java
public static Claim submit(String claimRef, String policyNumber,
                           String claimantName, BigDecimal amountClaimed,
                           String description) {
    if (amountClaimed == null || amountClaimed.signum() <= 0) {
        throw new IllegalArgumentException("amountClaimed must be > 0");
    }
    // ... more guards
    return new Claim(claimRef, policyNumber, claimantName, amountClaimed, description);
}
```

Why? Because a public constructor cannot enforce the *business rules* of creation — it's just allocation. A factory method names the *intent* (`submit` — not `new Claim`), validates the inputs, and returns an object that is already in a valid domain state.

I also added `@Version` for optimistic locking. In a concurrent system where two adjusters might touch the same claim simultaneously, you need the database to protect you, not just hope for the best.

---

### The State Machine: Why I Chose an Enum over the GoF State Pattern

The GoF State pattern is elegant in theory. In practice, for a bounded set of workflow states, it generates five times the code for the same outcome.

My approach: a single `Map<ClaimStatus, Set<ClaimStatus>>` on the enum itself. One source of truth, zero ceremony:

```java
public enum ClaimStatus {
    SUBMITTED, UNDER_REVIEW, ADJUDICATION, APPROVED, DENIED;

    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED = Map.of(
        SUBMITTED,    Set.of(UNDER_REVIEW, DENIED),
        UNDER_REVIEW, Set.of(ADJUDICATION, DENIED),
        ADJUDICATION, Set.of(APPROVED, DENIED),
        APPROVED,     Set.of(),   // terminal
        DENIED,       Set.of()    // terminal
    );

    public boolean canTransitionTo(ClaimStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
```

When `Claim.transitionTo(target)` is called, it checks this map. Illegal transition → `InvalidClaimTransitionException`. Legal transition → state changes, `updatedAt` bumps, previous status returned for event logging. That's it.

The **Memento equivalent** isn't a pattern here — it's the `claim_events` table. Every state change writes an immutable `ClaimEvent` row. Full audit trail, full replay capability, 7-year retention ready. That's the real insurance industry requirement.

---

### The Fraud Chain: Zero-Touch Extensibility

Fraud scoring is where things get interesting. The business wants 15+ indicators eventually: high-value amounts, duplicate policies, suspicious timing, known fraud postcodes, round-number amounts. Adding all of these one by one into a giant service method would be a maintenance nightmare.

I used **Chain of Responsibility** via Spring's auto-discovery:

```java
@Component
public class FraudScoringChain {
    private final List<FraudIndicator> indicators;

    public FraudScoringChain(List<FraudIndicator> indicators) {
        // Spring injects EVERY bean that implements FraudIndicator
        this.indicators = List.copyOf(indicators);
    }

    public FraudScoreResult score(Claim claim) {
        int total = 0;
        for (FraudIndicator indicator : indicators) {
            try {
                FraudIndicatorResult result = indicator.evaluate(claim);
                total += result.score();
            } catch (RuntimeException ex) {
                log.warn("Indicator {} failed; treating as clean", indicator.name(), ex);
                // Safe degradation: one broken indicator doesn't fail the whole chain
            }
        }
        return new FraudScoreResult(Math.min(100, total), results);
    }
}
```

Now I have three indicators live: `AmountThresholdIndicator` (score 30 for claims > $50K), `DuplicateClaimIndicator` (score 40 for same policy within 24 hours), `TimingPatternIndicator` (score 15 for midnight-to-5am UTC submissions). Adding indicator #4 is one new `@Component` class — nothing else changes.

If any indicator throws (database hiccup, NPE, whatever), the exception is caught and that indicator is treated as clean. Fraud scoring never blocks claim ingestion. That's the correct production stance.

---

### The Bug That Humbled Me: Java Lambda Type Inference

I wrote the `FraudScoringChainTest` using what I thought was a clean inline approach:

```java
// What I wrote
var indicators = List.of(
    (FraudIndicator) claim -> FraudIndicatorResult.triggered("A", 30, "high value"),
    (FraudIndicator) claim -> FraudIndicatorResult.triggered("B", 40, "duplicate")
);
```

Compiler error. `List.of()` with multiple lambdas infers `List<Object>` — Java cannot unify the target types of the individual lambdas into a common functional interface type when they're in the same `List.of()` call. The cast on each individual element isn't enough for the compiler to infer the list's type parameter.

**The fix:** declare the typed variable explicitly.

```java
// What actually works
List<FraudIndicator> indicators = List.of(
    claim -> FraudIndicatorResult.triggered("A", 30, "high value"),
    claim -> FraudIndicatorResult.triggered("B", 40, "duplicate")
);
```

Simple in hindsight. The lesson: Java's type inference for generics is powerful but it flows *from* a declared target type — not *through* a call chain of inferred types. When in doubt, declare your types explicitly and let the lambdas inherit them.

---

### REST API: No Sycophantic Endpoints

I see a lot of portfolios with REST APIs that are all `@GetMapping` happy paths and no error handling. That's not how real systems work.

Every endpoint in `ClaimController` has a corresponding failure path:
- `POST /claims` → `400` on invalid input (Bean Validation + `@RestControllerAdvice`)
- `GET /claims/{ref}` → `404` when the claim doesn't exist
- `POST /claims/{ref}/transitions` → `409` on illegal state transition
- `POST /claims/{ref}/transitions` (approve with stale data) → `409` from `OptimisticLockingFailureException`

All errors return **RFC-7807 ProblemDetail** — the standard the industry is moving to:

```json
{
  "type":   "about:blank",
  "title":  "Invalid Claim Transition",
  "status": 409,
  "detail": "Claim CLM-3F7A cannot transition from APPROVED to SUBMITTED",
  "instance": "/api/v1/claims/CLM-3F7A/transitions"
}
```

No raw exception messages leaking to clients. No generic "something went wrong." Precise, machine-readable, loggable.

---

### Tests: 23 Passing, Zero Real Infrastructure

Every test runs with:
- `@ExtendWith(MockitoExtension.class)` — no Spring context overhead
- H2 in-memory (for the Spring smoke test) — no Docker, no MySQL
- Mockito for all collaborators

The 11-case `ClaimTest` suite validates every legal and illegal state transition, invariant violations, and edge cases. The fraud chain tests validate aggregation, exception safety, and individual indicator logic. The application service tests verify the entire ingestion flow without touching a real database.

`mvn clean verify` → 23 green, zero infrastructure, under 8 seconds.

---

### Week 1 Takeaway

The patterns I used this week — static factory, enum state machine, Chain of Responsibility, constructor injection, RFC-7807 errors — are not academic. They're the patterns that distinguish a production Java system from a tutorial project.

Next week: event-driven architecture. The Transactional Outbox pattern, SQS FIFO integration, OpenSearch CQRS read model, and AWS Bedrock AI summarization. The system is about to get genuinely interesting.

---

**Tags:** `#Java` `#SpringBoot` `#DomainDrivenDesign` `#DesignPatterns` `#BFSI` `#InsuranceTech` `#CleanCode` `#SoftwareArchitecture` `#Java21` `#UnitTesting`

---
---

## Week 2 — Zero Data Loss with the Transactional Outbox, CQRS on OpenSearch, and AI Claims Analysis via AWS Bedrock

### *Why dual-write is wrong, how I guaranteed event delivery without 2PC, and what happens when you build with the wrong API signature*

---

Week 1 gave me a solid domain core. But a claims system that can't reliably publish events, can't search at scale, and can't leverage AI is not a real system. Week 2 changed all of that.

Four major additions this week:
1. **Transactional Outbox** — guaranteed, at-least-once event delivery to SQS with zero data loss
2. **SQS FIFO** — per-claim event ordering across the relay window
3. **OpenSearch CQRS read model** — full-text, multi-field, faceted claim search
4. **AWS Bedrock Converse API** — Claude-powered structured claim analysis

Here's how each piece was built, the architecture decisions behind them, and the two bugs that cost me real time.

---

### The Pattern I See Broken in 80% of Event-Driven Systems

Ask most teams how they publish domain events to a message broker and you'll hear something like this:

> "We save to the database, then we call SQS."

That's **dual-write**. And it's wrong.

If SQS fails *after* the database commit, you've lost the event. Silently. No error in the business flow — the claim was saved, the `201 Created` was returned — but the projection never updated, the audit never fired, the downstream consumer never saw it.

The correct solution is the **Transactional Outbox**:

```java
@Transactional
public Claim ingest(IngestClaimCommand cmd, String actorId) {
    // Step 1: Business write
    Claim saved = claimRepository.save(claim);
    
    // Step 2: Fraud scoring, audit events
    claimEventRepository.save(ClaimEvent.submitted(saved.getId(), actorId));
    
    // Step 3: OutboxClaimEventPublisher writes to outbox_events
    //         This is in the SAME transaction as step 1 and 2.
    //         All three commit or all three roll back. No 2PC needed.
    eventPublisher.publish(submitted);
}
```

The `OutboxClaimEventPublisher` doesn't call SQS. It writes a row to `outbox_events` inside the caller's `@Transactional`. That's it. Atomic.

A separate `OutboxRelayScheduler` polls `outbox_events` every 2 seconds and forwards pending rows to SQS:

```java
@Scheduled(fixedDelayString = "${claimsflow.outbox.relay-interval-ms:2000}")
@Transactional
public void relay() {
    List<OutboxEvent> pending = outboxEventRepository.findPendingBatch();
    for (OutboxEvent event : pending) {
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(event.getPayload())
                .messageGroupId(event.getAggregateId())        // per-claim FIFO ordering
                .messageDeduplicationId(String.valueOf(event.getId()))  // SQS dedup
                .build());
            event.markSent();
        } catch (Exception ex) {
            event.markFailed(); // retryCount++ — up to 3 attempts
        }
    }
}
```

Now consider the failure modes:
- **SQS down?** Row stays `PENDING`. Next tick retries. Business write is unaffected.
- **App crashes mid-relay?** Row stays `PENDING`. Next startup picks it up.
- **SQS accepts, but app crashes before `markSent()`?** Row is re-sent on next tick. Consumer must be idempotent (which it will be — Week 3).

This is the *only* correct way to guarantee event delivery without a distributed transaction. CDC (Debezium + Kafka) is also correct but operationally far heavier. For SQS-based workloads, the outbox is the right call.

---

### Why SQS FIFO and Not Standard?

The choice between SQS Standard and FIFO matters for claims.

Consider what happens when a claim is submitted and immediately reviewed by an auto-adjudicator:
- Event 1: `CLAIM_SUBMITTED` (outbox writes, relay sends)
- Event 2: `CLAIM_TRANSITIONED` to `UNDER_REVIEW` (milliseconds later)

With a Standard queue, Event 2 could arrive at the consumer *before* Event 1. The projection would try to mark a claim as `UNDER_REVIEW` before it was ever `SUBMITTED`. Chaos.

FIFO with `messageGroupId=claimRef` guarantees per-claim ordering. `messageDeduplicationId=outboxId` (a unique Long PK from the outbox table) prevents the relay from sending the same message twice if the scheduler fires twice in rapid succession.

The trade-off: FIFO throughput is 3,000 messages/second per queue (300/s per message group). For 500 claims/minute, that's 500 messages/minute — nowhere near the limit. If we ever need more, SQS supports batching and we can fan out with SNS.

---

### OpenSearch CQRS: The Read Model MySQL Can't Be

MySQL is the right tool for transactional writes. It is the wrong tool for:
- Full-text search across claim descriptions and AI summaries
- Scoring results by relevance (claimant name more important than description)
- Faceted filtering by status + fraud flag simultaneously
- Dashboard aggregations across millions of claims

I projected a denormalized `ClaimDocument` to OpenSearch — one document per claim, keyed by `claimRef`. The write side is normalized (MySQL). The read side is denormalized (OpenSearch). That's CQRS in its simplest, most useful form.

The search query uses the OpenSearch Java Client's Query DSL — never string concatenation:

```java
Query multiMatch = Query.of(q -> q.multiMatch(m -> m
    .query(queryText)
    .fields("claimantName^3", "policyNumber^2", "description", "aiSummary")
    .type(TextQueryType.BestFields)
    .fuzziness("AUTO")));

Query boolQuery = Query.of(q -> q.bool(b -> b
    .must(multiMatch)
    .filter(List.of(
        Query.of(f -> f.term(t -> t.field("status").value(FieldValue.of("UNDER_REVIEW")))),
        Query.of(f -> f.term(t -> t.field("fraudFlagged").value(FieldValue.of(true))))
    ))));
```

`claimantName^3` means a match on claimant name scores three times more than a description match. `policyNumber^2` ranks policy number matches above free text. `fuzziness=AUTO` handles typos. `FieldValue.of(true)` is typed — not a string — so it works correctly with boolean fields.

This is the exact query pattern I'd use on a production OpenSearch cluster with 9+ indices. String concatenation is a security risk (injection) and breaks silently on special characters. Always use the typed Query DSL.

---

### AWS Bedrock: The Converse API and Why Temperature Matters

For AI summarization, I chose the **Converse API** over `InvokeModel`. This is a deliberate architectural decision.

`InvokeModel` is model-specific — the request/response schema is different for Claude, Titan, Llama, etc. Swapping models means rewriting the serialization logic.

`Converse API` is model-agnostic. Changing `model-id` in `application.yml` is all it takes to switch from Claude Haiku (cost-optimized) to Claude Sonnet (accuracy-optimized):

```java
ConverseRequest.builder()
    .modelId(modelId)  // externalized to config — one-line swap
    .messages(Message.builder()
        .role(ConversationRole.USER)
        .content(ContentBlock.fromText(prompt))
        .build())
    .inferenceConfig(InferenceConfiguration.builder()
        .temperature(0.2f)   // the key parameter
        .maxTokens(500)
        .build())
    .build()
```

**Why `temperature=0.2`?** Temperature controls randomness in the model's output. High temperature (0.8–1.0) is for creative writing — varied, surprising outputs. Low temperature (0.1–0.3) is for extraction and classification — deterministic, structured JSON.

I'm asking Claude to produce:
```json
{
  "keyFacts": "...",
  "coverageMatch": "...",
  "redFlags": "...",
  "recommendedAction": "AUTO_APPROVE | MANUAL_REVIEW | SIU_REFERRAL | DENY"
}
```

At temperature 0.8, I'd get different `recommendedAction` values for the same claim on different invocations. At temperature 0.2, the output is stable and parseable. For structured extraction, always use low temperature.

The summarizer is wrapped with `@CircuitBreaker` + `@Retry` (Resilience4j). If Bedrock is unavailable:

```java
@CircuitBreaker(name = "bedrockSummarizer", fallbackMethod = "fallbackSummary")
@Retry(name = "bedrockSummarizer")
public String summarize(Claim claim) { ... }

private String fallbackSummary(Claim claim, Throwable ex) {
    return """
        {"keyFacts":"Summary unavailable","coverageMatch":"N/A",
         "redFlags":"N/A","recommendedAction":"MANUAL_REVIEW"}""";
}
```

The claim is never blocked waiting for AI. The fallback fires, the claim proceeds with a degraded-mode summary, and an adjuster reviews it manually. Bedrock recovery resets the circuit automatically.

---

### Bug #1: The OpenSearch API That Didn't Exist

I wrote `OpenSearchConfig` based on what I remembered from working with the Apache HttpClient5 transport. My first attempt:

```java
// What I wrote
CloseableHttpClient httpClient = HttpClients.createDefault();
URI uri = URI.create(endpoint);
var transport = ApacheHttpClient5TransportBuilder.builder(httpClient, uri).build();
```

Compilation error: `builder(CloseableHttpClient, URI)` — this method signature does not exist.

The correct API takes `HttpHost` varargs:

```java
// What actually works
HttpHost host = HttpHost.create(endpoint);
var transport = ApacheHttpClient5TransportBuilder.builder(host).build();
return new OpenSearchClient(transport);
```

The lesson: don't trust memory when dealing with specific SDK APIs. The OpenSearch Java client's transport builder API is `HttpHost`-based, not URI-based. It's a one-liner fix but the kind of thing that doesn't show up until you actually try to compile it against the real library.

---

### Bug #2: The Profile That Wasn't Switching

I had `OpenSearchClaimSearchRepository` annotated `@Profile("!test")` and `NoOpClaimSearchRepository` annotated `@Profile("test")`. Running the Spring context test:

```
NoUniqueBeanDefinitionException: expected single matching bean but found 2:
openSearchClaimSearchRepository, noOpClaimSearchRepository
```

Both beans were loading. The `!test` profile expression was being evaluated before the test profile was fully active.

The fix was ensuring the test profile was declared *first* in `application-test.yml`:

```yaml
spring:
  profiles:
    active: test   # must be set before Spring evaluates @Profile conditions
```

The root cause: Spring evaluates `@Profile("!test")` based on the active profiles at bean registration time. If the test profile is set late (e.g., via a `@TestPropertySource` that loads after `@Profile` evaluation), both beans can register. Explicit `spring.profiles.active=test` in `application-test.yml` resolves this reliably.

---

### Profile-Based Bean Switching: The Right Way to Handle AWS in Tests

A pattern I see abused constantly: `@MockBean` sprinkled throughout test classes to override AWS beans. This pollutes production bean definitions and creates hidden coupling between tests and production wiring.

The correct pattern is **profile-based adapter switching**:

| Interface | `@Profile("!test")` (real) | `@Profile("test")` (stub) |
|---|---|---|
| `ClaimEventPublisher` | `OutboxClaimEventPublisher` | `LoggingClaimEventPublisher` |
| `ClaimSearchRepository` | `OpenSearchClaimSearchRepository` | `NoOpClaimSearchRepository` |
| `ClaimsSummarizer` | `BedrockClaimsSummarizer` | `StubClaimsSummarizer` |

In tests, `BedrockRuntimeClient` is mocked directly via Mockito — only in the tests that specifically need to verify Bedrock interactions. Everything else uses stubs that never touch AWS at all.

Result: **37 tests, zero real AWS, zero Docker, all green in under 10 seconds.**

That's the standard. Any test suite that requires real cloud infrastructure to run is a liability in a CI/CD pipeline.

---

### 14 New Tests, No Flakiness

The 14 new tests added this week cover:

- **`OutboxClaimEventPublisherTest`**: verifies the outbox row has the correct `aggregateId`, `eventType`, and valid JSON payload
- **`OutboxRelaySchedulerTest`**: verifies SQS `sendMessage` is called with the correct `messageGroupId` and `messageDeduplicationId`; verifies `markSent()` on success and `markFailed()` on SQS exception; verifies `retryFailed()` resets to `PENDING`
- **`ClaimProjectionServiceTest`**: verifies `ClaimDocument` fields are mapped correctly from the Claim aggregate; verifies fraud flag is set when score ≥ threshold
- **`BedrockClaimsSummarizerTest`**: verifies the `ConverseRequest` uses the correct `modelId` and that the prompt contains key claim fields
- **`AiSummarizationServiceTest`**: create new `AiSummary`, update existing, throw `ClaimNotFoundException` on unknown ref

All pure Mockito. No slow Spring contexts. No flakiness.

---

### Week 2 Takeaway

The Transactional Outbox is the single most important pattern in event-driven architecture. Get it wrong and your events are unreliable. Get it right and you can tolerate any downstream failure without data loss.

CQRS with OpenSearch isn't complexity for its own sake — it solves a real problem (MySQL cannot do full-text search + aggregations at scale) with a technology that is designed for exactly that workload.

The Bedrock Converse API is genuinely good engineering — model-agnostic, temperature-tunable, easy to swap. At `temperature=0.2`, Claude gives you consistent, parseable JSON every time.

Week 3 next: `@SqsListener` consumer (the other end of the outbox relay), Customer360 bounded context, WebSocket real-time dashboard, and Testcontainers integration tests (once Docker Desktop is sorted).

---

**Tags:** `#AWS` `#Bedrock` `#OpenSearch` `#SQS` `#CQRS` `#EventDrivenArchitecture` `#TransactionalOutbox` `#SpringBoot` `#Java21` `#ClaudeAI` `#GenAI` `#InsuranceTech` `#BFSI` `#DistributedSystems` `#SoftwareArchitecture`

---
---

## Week 3 — Closing the CQRS Loop: The SQS Consumer, a Scheduler-Starvation Trap, and Why Money Never Reads from the Search Index

### *The other end of the outbox relay, poison messages vs transient failures, and a Mockito strict-stubbing bug that made my test lie to me*

---

At the end of Week 2, ClaimsFlow360 had a strange gap: the Transactional Outbox faithfully relayed every claim event to SQS FIFO — and *nothing was listening*. The OpenSearch projection only refreshed when someone called a manual REST endpoint. An event-driven architecture where nobody consumes the events is just an expensive audit log.

Week 3 closed the loop. Four deliverables:

1. **SQS long-poll consumer** — events arrive, projections refresh, automatically
2. **Reconciliation job** — the 6-hour safety net every eventually-consistent system needs
3. **Customer360** — the aggregated policyholder view (FR-05)
4. **WebSocket dashboard** — live claim metrics over STOMP (FR-06)

And two genuinely instructive bugs. Let's go.

---

### The Trap: A Long Poll Inside @Scheduled

My first instinct for the consumer was the obvious one — the codebase already had a `@Scheduled` outbox relay, so why not a `@Scheduled` consumer?

```java
// The trap — DO NOT do this
@Scheduled(fixedDelay = 1000)
public void consume() {
    sqsClient.receiveMessage(ReceiveMessageRequest.builder()
        .waitTimeSeconds(10)   // ← long poll blocks for up to 10 seconds
        .build());
    ...
}
```

Here's the problem: Spring Boot's default `TaskScheduler` has a pool size of **one thread**. Every `@Scheduled` method in the application shares it. A long poll that blocks for 10 seconds doesn't just delay itself — it starves *every other scheduled job*. The outbox relay that's supposed to fire every 2 seconds? It now fires whenever the consumer's long poll happens to release the thread. Under sustained event flow, the relay effectively stops.

This is the kind of bug that passes every unit test and only shows up as "events are mysteriously slow in staging."

The fix has two parts. First, the consumer gets its **own dedicated thread** via Spring's `SmartLifecycle`:

```java
@Component
@Profile("!test")
public class ClaimEventSqsConsumer implements SmartLifecycle {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "claim-event-sqs-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;

    @Override
    public void start() {
        running = true;
        executor.submit(this::pollLoop);   // continuous long-poll loop
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();            // clean shutdown with the context
    }
}
```

`SmartLifecycle` gives you container-managed startup and shutdown — the loop starts when the Spring context is ready and stops gracefully when it closes. No `@PostConstruct` raciness, no orphaned threads on redeploy.

Second, the scheduler pool got sized properly anyway (`spring.task.scheduling.pool.size: 4`), because by end of week there were four scheduled jobs: outbox relay, outbox retry, reconciliation, dashboard heartbeat. Never let independent background jobs serialize behind each other by default.

---

### Poison vs Transient: Not All Failures Deserve a Retry

At-least-once delivery forces you to answer a question most tutorials skip: *when processing fails, do you delete the message or leave it?*

The answer depends on **why** it failed, and getting it wrong on a FIFO queue is expensive — an undeletable poison message blocks its entire message group forever. That's per-claim ordering working against you.

My consumer discriminates:

| Failure | Classification | Action |
|---|---|---|
| Unparseable JSON payload | **Poison** — retrying will never help | Delete + ERROR log (DLQ in prod) |
| Event references a claim that doesn't exist | **Poison** — outbox rows commit *after* the claim insert, so a missing claim is corruption, not a race | Delete + ERROR log |
| OpenSearch is down | **Transient** — will heal | Leave on queue; SQS redelivers after the visibility timeout |

The transient case has a subtle bonus: while the message sits on the queue, its FIFO message group is blocked — which means *no later event for that claim can jump ahead*. The "failure mode" is actually the ordering guarantee doing its job.

And idempotency? The projection is an upsert keyed by `claimRef`. Processing the same event twice re-writes the same document. Duplicate delivery is a non-event — which is the only sane way to live with at-least-once semantics.

---

### The Reconciliation Job: Trust, but Verify

Even with the consumer running, an eventually-consistent read model accumulates drift: a poison message deleted without projecting, an OpenSearch outage that outlasts SQS retention, a bug you haven't found yet. You don't hope drift away — you *sweep* it away.

```java
@Scheduled(fixedDelayString = "${claimsflow.reconciliation.interval-ms:21600000}")  // 6h
public void reconcile() {
    Instant since = Instant.now().minus(Duration.ofHours(lookbackHours));  // 7h
    for (Claim claim : claimRepository.findByUpdatedAtAfter(since)) {
        try {
            projectionService.project(claim.getClaimRef());
        } catch (RuntimeException ex) {
            log.error(...);   // one bad claim never aborts the sweep
        }
    }
}
```

Two deliberate numbers: the sweep runs every **6 hours** but looks back **7 hours**. The overlap means a claim updated seconds before a sweep is still covered by the next one. Since re-projecting is idempotent, over-projecting costs a few wasted upserts; under-projecting is silent data drift. Cheap insurance.

---

### Customer360: The Read Model You *Don't* Build on the Search Index

Here's the Week 3 decision I'd defend hardest in an architecture review.

FR-05 calls for an aggregated policyholder view: claim counts, financial totals, fraud exposure. The reflexive CQRS answer is "aggregate it in OpenSearch — that's what the read model is for."

I built it on **MySQL** instead. Deliberately.

The reasoning: the Customer360 view shows a policyholder *their money* — total claimed, total approved. OpenSearch lags the write model by up to ~2 seconds. A customer who just got a claim approved and sees a stale total isn't experiencing "eventual consistency" — they're experiencing a bug, and they'll call support about it. Financial figures shown to their owner must be strongly consistent.

And the cost of the consistent path is trivial: one policyholder has tens of claims, not millions. One indexed query (`findByPolicyNumberOrderByCreatedAtDesc`), one in-memory pass, done.

The rule that falls out of this: **choose the data store per query based on its consistency requirement, not per architecture diagram.** OpenSearch keeps portfolio-wide search and analytics, where 2-second staleness is invisible. MySQL keeps anything with a currency symbol and a name attached.

---

### The WebSocket Dashboard: Two Triggers, One Broadcast

FR-06 wanted live metrics — claims by workflow state, approval rate, fraud exposure. STOMP over WebSocket: clients connect to `/ws`, subscribe to `/topic/metrics`.

The interesting choice was *when to push*. Two triggers feed one broadcaster:

1. **Event-driven** — the SQS consumer broadcasts after every processed claim event. Sub-second dashboard updates when things happen.
2. **Heartbeat** — a 10-second scheduled broadcast. Dashboards converge even when no events flow (and freshly-connected clients don't stare at nothing).

One more production detail: the broadcast is wrapped in try/catch and *swallows* failures. Losing one dashboard frame is harmless; letting a WebSocket hiccup break SQS event processing is not. Know which of your side effects are load-bearing.

---

### The Bug That Made My Test Lie: Mockito Strict Stubbing

The reconciliation test needed: three claims, the middle one fails to project, the sweep continues. Obvious setup:

```java
// What I wrote — looks correct, isn't
doThrow(new RuntimeException("upsert failed"))
        .when(projectionService).project("CLM-BAD");
```

The test *ran*. The logs said: `1 re-projected, 2 failed`. Expected: 2 re-projected, 1 failed. Wait — why did CLM-A fail?

Because of **Mockito strict stubbing**. When a mock has a stubbing for `project("CLM-BAD")` and the code calls `project("CLM-A")`, Mockito's default strictness doesn't return quietly — it throws `PotentialStubbingProblem` *from inside the mocked call*. My production code dutifully caught that as a projection failure and kept sweeping. The exception designed to protect my test had become test data.

This is nastier than a normal test bug: resilient production code (catch-and-continue) *absorbs* the framework's complaint, so nothing fails loudly — the counts are just wrong.

The fix — stub the full argument space with one conditional answer, so every call matches a stubbing:

```java
doAnswer(inv -> {
    if (inv.getArgument(0).equals("CLM-BAD")) {
        throw new RuntimeException("upsert failed");
    }
    return null;
}).when(projectionService).project(anyString());
```

The lesson: when the code under test catches exceptions broadly *and* you're stubbing per-argument behavior on a strict mock, the two interact. Either stub the whole argument space or expect the framework's noise to leak into your assertions.

---

### Scheduling Hygiene: The @EnableScheduling That Was Riding Shotgun

Small refactor with an outsized rationale: `@EnableScheduling` had been sitting on `OutboxRelayScheduler` since Week 2. It worked — but it meant *deleting that one class would silently disable every scheduled job in the application*. The reconciliation sweep, the dashboard heartbeat — all of them owed their existence to an annotation on an unrelated component.

Now there's a dedicated `SchedulingConfig`: one `@Configuration` class whose only job is the cross-cutting switch. Boring code, but the principle matters: **application-wide capabilities never belong on an arbitrary component that happens to use them.**

---

### Week 3 Scoreboard

- **52 tests** (up from 37), still zero real AWS, zero Docker, all green
- The CQRS loop is closed: write → outbox → SQS → consumer → projection → dashboard push
- Two new API surfaces: `GET /customers/{policyNumber}/view`, `GET /dashboard/metrics` + `/topic/metrics` over WebSocket
- Testcontainers integration tests remain deferred — still fighting the Docker Desktop installer

Week 4 next: S3 document upload with Textract OCR, the SNS notification engine with DLQ retries, AES-256 field-level encryption for PII, and JWT auth on the WebSocket CONNECT frame. The platform grows teeth.

---

**Tags:** `#AWS` `#SQS` `#CQRS` `#EventDrivenArchitecture` `#SpringBoot` `#WebSocket` `#Java21` `#Mockito` `#DistributedSystems` `#EventualConsistency` `#InsuranceTech` `#BFSI` `#SoftwareArchitecture` `#TestingPitfalls`

---
---

## Architecture Flashcard — Week 1 & 2 Design Decisions at a Glance

### *Quick-reference for every major decision made in the first two weeks*

---

The two longer posts above go deep on the *how*. This one is for anyone who wants the *what and why* in one page — suitable for sharing as a standalone LinkedIn post or referencing in a tech review.

---

### Decision 1: Modular Monolith over Microservices

**What:** Single Spring Boot deployable with package-level bounded context boundaries.  
**Why:** No distributed transaction overhead. Single JVM transaction boundary. Strict package structure enforces the same discipline as microservices. Migration path to microservices is clear — extract a package, replace in-process calls with REST/SQS.  
**When to revisit:** When a single bounded context needs to scale independently, or when team size grows past 8 engineers per context.

---

### Decision 2: Enum State Machine over GoF State Pattern

**What:** `Map<ClaimStatus, Set<ClaimStatus>>` on the enum as single source of truth.  
**Why:** 5× less code, identical invariant enforcement, same auditability. The Memento (history/replay) is the `claim_events` append-only table — not a pattern embedded in the state objects.  
**When to revisit:** When transitions require complex *entry/exit behaviors* that differ per state (GoF State shines here). A boolean transition map is too simple for that.

---

### Decision 3: Chain of Responsibility for Fraud

**What:** Spring auto-injects `List<FraudIndicator>`. Chain aggregates scores, caps at 100.  
**Why:** Adding indicator #4–15 is one new `@Component` — zero changes to the chain. Each indicator is independently unit-tested. Exception in one indicator = "clean" result (safe degradation, never blocks ingestion).  
**When to revisit:** When indicator *ordering* matters (Chain of Responsibility has inherent ordering; current implementation is position-based by Spring's bean ordering).

---

### Decision 4: Transactional Outbox over Dual-Write

**What:** `outbox_events` row written in the same `@Transactional` as the business write. Relay scheduler forwards to SQS separately.  
**Why:** Dual-write (save to DB, then call SQS directly) has a failure window between the two operations. The outbox closes that window — both commit or neither does. At-least-once delivery, no 2PC.  
**When to revisit:** At high throughput, the relay becomes a bottleneck. Upgrade path: CDC with Debezium reads the binlog directly — no polling.

---

### Decision 5: SQS FIFO over Standard

**What:** `messageGroupId=claimRef` for per-claim event ordering. `messageDeduplicationId=outboxId` for relay dedup.  
**Why:** Standard queue can reorder events. `CLAIM_TRANSITIONED` arriving before `CLAIM_SUBMITTED` breaks projection logic. FIFO guarantees order within a message group.  
**When to revisit:** At > 3,000 messages/second (FIFO queue limit). Upgrade path: SNS → multiple Standard SQS queues with consumer-side idempotency.

---

### Decision 6: OpenSearch as CQRS Read Model

**What:** Denormalized `ClaimDocument` projected to OpenSearch `claims` index. MySQL stays the write model.  
**Why:** MySQL cannot efficiently do: multi-field full-text search, relevance scoring, faceted aggregations, dashboard metrics. OpenSearch is purpose-built for all of these. Eventual consistency (< 2 s lag) is acceptable for search UI.  
**When to revisit:** If regulatory audit requires search results to be strongly consistent. In that case: MySQL read replica for compliance queries, OpenSearch for UX.

---

### Decision 7: Bedrock Converse API over InvokeModel

**What:** `BedrockRuntimeClient.converse(ConverseRequest)` instead of `invokeModel`.  
**Why:** Converse API is model-agnostic. Swapping Claude Haiku → Claude Sonnet is a one-line config change. InvokeModel requires model-specific request/response serialization.  
**When to revisit:** When using Bedrock features that Converse API doesn't expose (e.g., streaming with specific tool use configurations). `invokeModelWithResponseStream` is still model-specific.

---

### Decision 8: temperature=0.2 for AI Summarization

**What:** Low temperature setting on the Bedrock Converse request.  
**Why:** Insurance claim analysis is an *extraction and classification* task, not a creative writing task. Low temperature → deterministic, parseable JSON. High temperature → inconsistent `recommendedAction` values for the same claim on repeated calls.  
**Rule of thumb:** `temperature > 0.7` for creative content. `temperature < 0.3` for structured data extraction.

---

### Decision 9: Profile-Based Bean Switching over @MockBean

**What:** `@Profile("!test")` on real AWS beans; `@Profile("test")` on stubs. No `@MockBean` in production wiring.  
**Why:** `@MockBean` replaces beans in the application context — it changes the production bean graph and creates hidden coupling between test setup and production configuration. Profile-based switching is transparent — the production context and test context are completely separate.  
**Rule:** Use `@MockBean` only in tests that specifically need to verify Spring context interactions. For everything else, use profile-switched stubs.

---

**Tags:** `#SoftwareArchitecture` `#DesignPatterns` `#Java` `#SpringBoot` `#AWS` `#CQRS` `#DomainDrivenDesign` `#EventDrivenArchitecture` `#BFSI` `#InsuranceTech` `#EngineeringDecisions`

---

## Post Template — Coming Weeks

The following is a pre-planned story arc for upcoming weekly posts. Fill in the details after each week's delivery.

---

### Week 4 (Draft) — Documents, Notifications, and Security Hardening

**Story hook:** Claims don't arrive as JSON payloads in real life — they come with photos, PDFs, and hospital reports. This week I wire S3 document upload + Amazon Textract OCR, build an SNS multi-channel notification engine, and add AES-256 field-level encryption for PII fields.

**Technical themes to cover:**
- S3 presigned URL upload flow (client uploads directly, bypasses the API)
- Amazon Textract async job → SNS callback → structured data extraction
- SNS fanout → email, SMS, in-app channels; SQS DLQ for failed delivery retries
- AES-256 field-level encryption for `ssn` and `dob` fields (JPA `@Converter`)
- JWT auth on the WebSocket CONNECT frame (closing the Week 3 `/ws/**` permitAll)
- GitLab CI pipeline: build → test → JaCoCo coverage gate → Docker image → EB deploy
- Spring Security RBAC: `ROLE_ADJUSTER` (read + transition), `ROLE_ADMIN` (full access including denial)
- AWS Cognito: replacing the HS256 dev JWT with real OAuth2 issuer-uri
- Testcontainers: MySQL + LocalStack integration tests (carried from Week 3, pending Docker fix)

---

*This document is updated at the end of each delivery week. Star the repository to follow the build.*
