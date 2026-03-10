package com.dataprocessing.cache;

import com.dataprocessing.domain.SensitiveClassification;
import com.dataprocessing.domain.TripleKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineNoveltyCacheTest {

    private NoveltyCache cache;

    @BeforeEach
    void setUp() {
        cache = new CaffeineNoveltyCache();
    }

    @Test
    void unknownTriple_isNotContained() {
        var triple = new TripleKey("acct", "a", "b", SensitiveClassification.FIRST_NAME);
        assertThat(cache.contains(triple)).isFalse();
    }

    @Test
    void afterMarkSeen_isContained() {
        var triple = new TripleKey("acct", "a", "b", SensitiveClassification.FIRST_NAME);
        cache.markSeen(triple);
        assertThat(cache.contains(triple)).isTrue();
    }

    @Test
    void differentClassification_isNotContained() {
        var triple1 = new TripleKey("acct", "a", "b", SensitiveClassification.FIRST_NAME);
        var triple2 = new TripleKey("acct", "a", "b", SensitiveClassification.LAST_NAME);
        cache.markSeen(triple1);
        assertThat(cache.contains(triple2)).isFalse();
    }

    @Test
    void differentAccount_isNotContained() {
        var triple1 = new TripleKey("acct-1", "a", "b", SensitiveClassification.FIRST_NAME);
        var triple2 = new TripleKey("acct-2", "a", "b", SensitiveClassification.FIRST_NAME);
        cache.markSeen(triple1);
        assertThat(cache.contains(triple2)).isFalse();
    }
}
