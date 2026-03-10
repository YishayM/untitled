package com.dataprocessing.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveClassificationTest {

    @Test
    void knownSensitiveTypes_areRecognised() {
        assertThat(SensitiveClassification.isSensitive("FIRST_NAME")).isTrue();
        assertThat(SensitiveClassification.isSensitive("LAST_NAME")).isTrue();
        assertThat(SensitiveClassification.isSensitive("CREDIT_CARD_NUMBER")).isTrue();
        assertThat(SensitiveClassification.isSensitive("SOCIAL_SECURITY_NUMBER")).isTrue();
    }

    @Test
    void nonSensitiveTypes_areRejected() {
        assertThat(SensitiveClassification.isSensitive("DATE")).isFalse();
        assertThat(SensitiveClassification.isSensitive("NUMBER")).isFalse();
        assertThat(SensitiveClassification.isSensitive("UNKNOWN")).isFalse();
        assertThat(SensitiveClassification.isSensitive("")).isFalse();
        assertThat(SensitiveClassification.isSensitive(null)).isFalse();
    }

    @Test
    void of_returnsEmptyForNonSensitive() {
        assertThat(SensitiveClassification.of("DATE")).isEmpty();
        assertThat(SensitiveClassification.of("UNKNOWN")).isEmpty();
        assertThat(SensitiveClassification.of(null)).isEmpty();
    }

    @Test
    void of_returnsValueForSensitive() {
        assertThat(SensitiveClassification.of("FIRST_NAME"))
                .contains(SensitiveClassification.FIRST_NAME);
        assertThat(SensitiveClassification.of("CREDIT_CARD_NUMBER"))
                .contains(SensitiveClassification.CREDIT_CARD_NUMBER);
    }
}
