package com.dataprocessing.repository;

import com.dataprocessing.domain.TripleKey;

public interface SeenTriplesRepository {

    /**
     * Attempts to insert the triple into seen_triples.
     * Uses INSERT ... ON CONFLICT DO NOTHING as the atomic exactly-once guard.
     *
     * @return true if inserted (first time seen); false if the triple already existed
     */
    boolean recordIfAbsent(TripleKey triple);
}
