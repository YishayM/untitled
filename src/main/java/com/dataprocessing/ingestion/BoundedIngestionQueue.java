package com.dataprocessing.ingestion;

import java.util.concurrent.LinkedBlockingQueue;

public class BoundedIngestionQueue implements IngestionQueue {

    private final LinkedBlockingQueue<IncomingBatch> delegate;

    public BoundedIngestionQueue(int capacity) {
        this.delegate = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public boolean offer(IncomingBatch batch) {
        return delegate.offer(batch);
    }

    @Override
    public IncomingBatch take() throws InterruptedException {
        return delegate.take();
    }
}
