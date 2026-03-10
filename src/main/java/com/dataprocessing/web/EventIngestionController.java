package com.dataprocessing.web;

import com.dataprocessing.domain.SensorEvent;
import com.dataprocessing.ingestion.IncomingBatch;
import com.dataprocessing.ingestion.IngestionQueue;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class EventIngestionController {

    private final IngestionQueue ingestionQueue;

    public EventIngestionController(IngestionQueue ingestionQueue) {
        this.ingestionQueue = ingestionQueue;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> ingestEvents(
            @RequestBody List<SensorEvent> events,
            HttpServletRequest request) {

        String accountId = (String) request.getAttribute("accountId");
        boolean accepted = ingestionQueue.offer(new IncomingBatch(accountId, events));
        return accepted
                ? ResponseEntity.accepted().build()
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
