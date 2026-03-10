package com.dataprocessing.alert;

import com.dataprocessing.domain.SensorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Stub implementation active only while PR4 is not yet merged.
 * Replaced by the real AlertEngine in PR4 — remove this class then.
 */
@Component
@ConditionalOnMissingBean(AlertEngine.class)
public class NoOpAlertEngine implements AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(NoOpAlertEngine.class);

    @Override
    public void process(String accountId, SensorEvent event) {
        log.debug("NoOpAlertEngine: skipping event from {} → {} (PR4 not yet merged)",
                event.source(), event.destination());
    }
}
