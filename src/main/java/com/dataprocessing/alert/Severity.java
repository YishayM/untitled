package com.dataprocessing.alert;

/**
 * Alert severity for a sensitive data flow.
 *
 * <p>Escalation rule: {@link #HIGH} if either the source or destination service is
 * marked public-facing in the services registry; {@link #MEDIUM} if both are internal.
 * See {@link AlertSeverityResolver} for the routing logic.
 */
public enum Severity {
    /** Both source and destination are internal services. */
    MEDIUM,
    /** At least one endpoint (source or destination) is a public-facing service. */
    HIGH
}
