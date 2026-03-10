package com.dataprocessing.config;

import com.dataprocessing.ingestion.BoundedIngestionQueue;
import com.dataprocessing.ingestion.IngestionQueue;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class WorkerPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkerPoolConfig.class);

    // public visibility is required: WorkerStarter (com.dataprocessing.ingestion) references these
    // at startup. Ideally bind via @Value properties to allow per-environment override without recompile.
    public static final int QUEUE_CAPACITY = 50_000;
    public static final int N_WORKERS = 2 * Runtime.getRuntime().availableProcessors();

    @Bean
    public IngestionQueue ingestionQueue() {
        return new BoundedIngestionQueue(QUEUE_CAPACITY);
    }

    /**
     * UTC system clock injected into SensitiveDataAlertEngine (and any other time-aware
     * component). Using a bean instead of Instant.now() directly makes the clock
     * replaceable in tests for deterministic timestamp assertions.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Fixed thread pool sized to 2×CPU — workload is I/O-bound (DB inserts).
     * destroyMethod="" disables Spring's default shutdown; graceful drain is handled
     * by WorkerPoolShutdown @PreDestroy so in-flight batches are not discarded.
     * HikariCP pool size is set to N_WORKERS so no worker ever waits for a connection.
     */
    @Bean(destroyMethod = "")
    public ExecutorService workerPool(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.setMaximumPoolSize(N_WORKERS);
            log.info("WorkerPoolConfig: HikariCP maximumPoolSize set to {}", N_WORKERS);
        }
        AtomicInteger idx = new AtomicInteger();
        return Executors.newFixedThreadPool(N_WORKERS,
                r -> new Thread(r, "event-worker-" + idx.getAndIncrement()));
    }

    /**
     * Performs a graceful drain on context close:
     *   1. shutdown() — stop accepting new tasks, let queued tasks drain.
     *   2. awaitTermination(10 s) — wait for in-flight work to complete.
     *   3. shutdownNow() — interrupt any workers still running after the timeout.
     */
    @Bean
    public WorkerPoolShutdown workerPoolShutdown(ExecutorService workerPool) {
        return new WorkerPoolShutdown(workerPool);
    }

    public static class WorkerPoolShutdown {

        private static final Logger log = LoggerFactory.getLogger(WorkerPoolShutdown.class);

        private final ExecutorService workerPool;

        public WorkerPoolShutdown(ExecutorService workerPool) {
            this.workerPool = workerPool;
        }

        @jakarta.annotation.PreDestroy
        public void drain() throws InterruptedException {
            log.info("WorkerPoolShutdown: shutting down worker pool, draining queued batches...");
            workerPool.shutdown();
            if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("WorkerPoolShutdown: timeout waiting for workers — forcing shutdownNow()");
                workerPool.shutdownNow();
            } else {
                log.info("WorkerPoolShutdown: all workers finished cleanly");
            }
        }
    }
}
