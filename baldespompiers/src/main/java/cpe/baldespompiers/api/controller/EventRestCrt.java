package cpe.baldespompiers.api.controller;

import com.project.model.dto.EmergencyEventDto;
import fr.cpe.baldespompiers.thread.EventPollerThread;
import org.springframework.web.bind.annotation.*;

import java.util.List;


// EventRestCrt#getAllEvents sert de point de lecture REST pour l’état caché des événements d’urgence.
@RestController
@RequestMapping("/api/events")
public class EventRestCrt {

    private final EventPollerThread poller;

    public EventRestCrt(EventPollerThread poller) {
        this.poller = poller;
    }

    @GetMapping
    public List<EmergencyEventDto> getAllEvents() {
        return poller.getCachedEvents();
    }

//    EventPollerThread.pollEvents() tourne périodiquement avec @Scheduled
//    il appelle rpEventClient.getAllEvents()
//    il met à jour cachedEvents
//    EventRestCrt.getAllEvents() expose ce cache via /api/events
}

