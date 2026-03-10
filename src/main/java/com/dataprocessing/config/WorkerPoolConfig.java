package com.dataprocessing.config;

import com.dataprocessing.ingestion.BoundedIngestionQueue;
import com.dataprocessing.ingestion.IngestionQueue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class WorkerPoolConfig {

    public static final int QUEUE_CAPACITY = 50_000;
    public static final int N_WORKERS = 2 * Runtime.getRuntime().availableProcessors();

    @Bean
    public IngestionQueue ingestionQueue() {
        return new BoundedIngestionQueue(QUEUE_CAPACITY);
    }

    /**
     * Fixed thread pool sized to 2×CPU — workload is I/O-bound (DB inserts).
     * destroyMethod = "shutdownNow" interrupts workers on context close,
     * which triggers the InterruptedException exit path in EventWorker.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService workerPool() {
        AtomicInteger idx = new AtomicInteger();
        return Executors.newFixedThreadPool(N_WORKERS,
                r -> new Thread(r, "event-worker-" + idx.getAndIncrement()));
    }
}
