package com.dataprocessing.ingestion;

import com.dataprocessing.alert.AlertEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker runnable submitted once per thread to the fixed pool.
 * Loops on IngestionQueue.take(), forwarding each event to the AlertEngine.
 * Exits cleanly when the thread is interrupted (pool shutdown).
 */
public class EventWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(EventWorker.class);

    private final IngestionQueue queue;
    private final AlertEngine alertEngine;

    public EventWorker(IngestionQueue queue, AlertEngine alertEngine) {
        this.queue = queue;
        this.alertEngine = alertEngine;
    }

    @Override
    public void run() {
        log.info("EventWorker started on thread {}", Thread.currentThread().getName());
        while (!Thread.currentThread().isInterrupted()) {
            try {
                IncomingBatch batch = queue.take();
                for (var event : batch.events()) {
                    alertEngine.process(batch.accountId(), event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("EventWorker interrupted, shutting down: {}", Thread.currentThread().getName());
                return;
            }
        }
    }
}
