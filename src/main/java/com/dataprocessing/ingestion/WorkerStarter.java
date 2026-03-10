package com.dataprocessing.ingestion;

import com.dataprocessing.alert.AlertEngine;
import com.dataprocessing.config.WorkerPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * Starts the event worker pool after the full application context is ready
 * (DB pool initialized, caches warmed). Using ApplicationRunner guarantees
 * workers start after ServiceCacheWarmup has already populated the services cache.
 */
@Component
public class WorkerStarter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerStarter.class);

    private final IngestionQueue queue;
    private final AlertEngine alertEngine;
    private final ExecutorService workerPool;

    public WorkerStarter(IngestionQueue queue, AlertEngine alertEngine, ExecutorService workerPool) {
        this.queue = queue;
        this.alertEngine = alertEngine;
        this.workerPool = workerPool;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (int i = 0; i < WorkerPoolConfig.N_WORKERS; i++) {
            workerPool.submit(new EventWorker(queue, alertEngine));
        }
        log.info("Started {} event workers, queue capacity {}",
                WorkerPoolConfig.N_WORKERS, WorkerPoolConfig.QUEUE_CAPACITY);
    }
}
