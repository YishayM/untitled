package com.dataprocessing.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public enum SensitiveClassification {
    FIRST_NAME,
    LAST_NAME,
    CREDIT_CARD_NUMBER,
    SOCIAL_SECURITY_NUMBER;

    private static final Set<String> SENSITIVE_NAMES =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    public static boolean isSensitive(String value) {
        if (value == null) return false;
        return SENSITIVE_NAMES.contains(value);
    }

    public static Optional<SensitiveClassification> of(String value) {
        // Check the set first — avoids exception-driven control flow on the hot path.
        // valueOf() is only called when the name is known to be valid.
        if (!isSensitive(value)) return Optional.empty();
        return Optional.of(valueOf(value));
    }
}
