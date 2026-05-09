package com.claimsflow.claims.infra.search;

import java.util.List;

/**
 * Paginated search result wrapping OpenSearch hits.
 */
public record ClaimSearchResult(
        List<ClaimDocument> hits,
        long totalHits,
        int page,
        int size
) {}
