package com.dataprocessing.ingestion;

/**
 * Bounded in-process queue that decouples Tomcat HTTP threads from worker threads.
 * Offer is non-blocking; take blocks until a batch is available.
 */
public interface IngestionQueue {

    /**
     * Attempts to enqueue a batch without blocking.
     *
     * @return true if accepted; false if the queue is at capacity (caller should return 503)
     */
    boolean offer(IncomingBatch batch);

    /**
     * Removes and returns the next batch, blocking until one is available.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    IncomingBatch take() throws InterruptedException;
}
