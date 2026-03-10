package com.dataprocessing.web;

import com.dataprocessing.domain.SensorEvent;
import com.dataprocessing.ingestion.IncomingBatch;
import com.dataprocessing.ingestion.IngestionQueue;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class EventIngestionController {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionController.class);

    private final IngestionQueue ingestionQueue;

    public EventIngestionController(IngestionQueue ingestionQueue) {
        this.ingestionQueue = ingestionQueue;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> ingestEvents(
            @RequestBody List<SensorEvent> events,
            HttpServletRequest request) {

        String accountId = (String) request.getAttribute("accountId");
        if (accountId == null) {
            log.warn("Rejecting request: accountId attribute is missing — interceptor may not have run");
            return ResponseEntity.badRequest().build();
        }
        boolean accepted = ingestionQueue.offer(new IncomingBatch(accountId, events));
        if (!accepted) {
            log.warn("Ingestion queue full, rejecting batch for account={}", accountId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.accepted().build();
    }
}
