package com.dataprocessing.ingestion;

import com.dataprocessing.domain.SensorEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedIngestionQueueTest {

    @Test
    void offer_acceptsBatchWhenCapacityAvailable() {
        var queue = new BoundedIngestionQueue(10);
        var batch = new IncomingBatch("acct", List.of());
        assertThat(queue.offer(batch)).isTrue();
    }

    @Test
    void offer_returnsFalseWhenQueueFull() {
        var queue = new BoundedIngestionQueue(1);
        var batch = new IncomingBatch("acct", List.of());
        queue.offer(batch); // fills capacity
        assertThat(queue.offer(batch)).isFalse();
    }

    @Test
    void take_returnsEnqueuedBatchInOrder() throws InterruptedException {
        var queue = new BoundedIngestionQueue(10);
        var first  = new IncomingBatch("acct-1", List.of());
        var second = new IncomingBatch("acct-2", List.of());
        queue.offer(first);
        queue.offer(second);
        assertThat(queue.take()).isEqualTo(first);
        assertThat(queue.take()).isEqualTo(second);
    }

    @Test
    void offer_afterDrain_acceptsAgain() {
        var queue = new BoundedIngestionQueue(1);
        var batch = new IncomingBatch("acct", List.of());
        queue.offer(batch);
        assertThat(queue.offer(batch)).isFalse(); // full

        // drain one
        try { queue.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        assertThat(queue.offer(batch)).isTrue(); // space available again
    }
}
