package cpe.baldespompiers.api.controller;

import com.project.model.dto.FireDto;
import fr.cpe.baldespompiers.thread.EventPollerThread;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/fires")
public class FireRestCrt {

    private final EventPollerThread poller;

    public FireRestCrt(EventPollerThread poller) {
        this.poller = poller;
    }

    /** Retourne les feux actifs depuis le cache du poller */
    @GetMapping
    public List<FireDto> getAllFires() {
        return poller.getCachedFires();
    }
}
