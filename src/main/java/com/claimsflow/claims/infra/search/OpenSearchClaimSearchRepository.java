package com.claimsflow.claims.infra.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.*;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Production OpenSearch-backed repository.
 *
 * <p>Uses the OpenSearch Java client (Query DSL — not string concatenation).
 * The {@code claims} index is created automatically on first write if it does
 * not exist (OpenSearch auto-index creation; production should pre-create with
 * explicit mapping via an ILM/index template).
 */
@Slf4j
@Repository
@Profile("!test")
public class OpenSearchClaimSearchRepository implements ClaimSearchRepository {

    private final OpenSearchClient client;
    private final ObjectMapper objectMapper;

    public OpenSearchClaimSearchRepository(OpenSearchClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public void upsert(ClaimDocument document) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> docMap = objectMapper.convertValue(document, Map.class);

            IndexRequest<Map<String, Object>> req = IndexRequest.of(b -> b
                    .index(ClaimDocument.INDEX)
                    .id(document.getClaimRef())
                    .document(docMap));

            client.index(req);
            log.debug("OpenSearch upsert claimRef={} status={}", document.getClaimRef(), document.getStatus());
        } catch (IOException e) {
            throw new RuntimeException("OpenSearch upsert failed for claimRef=" + document.getClaimRef(), e);
        }
    }

    @Override
    public ClaimSearchResult search(ClaimSearchRequest request) {
        try {
            List<Query> filters = buildFilters(request);

            Query compositeQuery = buildCompositeQuery(request.query(), filters);

            SearchRequest searchRequest = SearchRequest.of(b -> b
                    .index(ClaimDocument.INDEX)
                    .query(compositeQuery)
                    .from(request.from())
                    .size(request.size())
                    .sort(s -> s.field(f -> f.field("createdAt").order(
                            org.opensearch.client.opensearch._types.SortOrder.Desc))));

            SearchResponse<Map> response = client.search(searchRequest, Map.class);

            List<ClaimDocument> hits = response.hits().hits().stream()
                    .map(this::mapHit)
                    .toList();

            long total = response.hits().total() != null ? response.hits().total().value() : 0L;
            return new ClaimSearchResult(hits, total, request.page(), request.size());

        } catch (IOException e) {
            throw new RuntimeException("OpenSearch search failed", e);
        }
    }

    // ─── Query DSL builders ───────────────────────────────────────────────────

    private List<Query> buildFilters(ClaimSearchRequest request) {
        List<Query> filters = new ArrayList<>();

        if (request.status() != null) {
            filters.add(Query.of(q -> q.term(t -> t
                    .field("status")
                    .value(FieldValue.of(request.status().name())))));
        }

        if (request.fraudOnly()) {
            filters.add(Query.of(q -> q.term(t -> t
                    .field("fraudFlagged")
                    .value(FieldValue.of(true)))));
        }

        return filters;
    }

    private Query buildCompositeQuery(String queryText, List<Query> filters) {
        if (queryText == null || queryText.isBlank()) {
            if (filters.isEmpty()) {
                return Query.of(q -> q.matchAll(m -> m));
            }
            return boolWithFilters(filters, null);
        }

        // Multi-field full-text search across key string fields
        Query multiMatch = Query.of(q -> q.multiMatch(m -> m
                .query(queryText)
                .fields("claimantName^3", "policyNumber^2", "description", "aiSummary")
                .type(TextQueryType.BestFields)
                .fuzziness("AUTO")));

        return boolWithFilters(filters, multiMatch);
    }

    private Query boolWithFilters(List<Query> filters, Query must) {
        return Query.of(q -> q.bool(b -> {
            if (must != null) b.must(must);
            if (!filters.isEmpty()) b.filter(filters);
            return b;
        }));
    }

    @SuppressWarnings("unchecked")
    private ClaimDocument mapHit(Hit<Map> hit) {
        Map<String, Object> src = hit.source();
        if (src == null) return null;
        return objectMapper.convertValue(src, ClaimDocument.class);
    }
}
