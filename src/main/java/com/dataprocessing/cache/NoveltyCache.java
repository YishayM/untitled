package com.dataprocessing.cache;

import com.dataprocessing.domain.TripleKey;

/**
 * Fast-path cache for seen (accountId, source, destination, classification) triples.
 * A cache hit means the triple was already processed — skip DB entirely.
 * A cache miss means we must check the DB (the authoritative exactly-once guard).
 *
 * The cache is not durable — lost on restart. The DB seen_triples table is the
 * source of truth; the cache is a performance optimisation only.
 */
public interface NoveltyCache {

    /** @return true if the triple is known to have been seen already */
    boolean contains(TripleKey triple);

    /** Mark a triple as seen after the DB confirms it was inserted (or conflicted). */
    void markSeen(TripleKey triple);
}
