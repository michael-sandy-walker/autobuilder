package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.event.FileChangeEvent;
import com.walpro.autobuilder.service.FileWatcherService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final FileWatcherService fileWatcherService;

    public EventController(FileWatcherService fileWatcherService) {
        this.fileWatcherService = fileWatcherService;
    }

    @Operation(
    	    summary = "Get recent file events",
    	    description = "Returns a list of recent file change events detected by the watcher."
    	)
    @ApiResponses({
    	@ApiResponse(responseCode = "200", description = "File events returned successfully")
    })
    @GetMapping("/recent")
    public List<FileChangeEvent> recentEvents() {
        return fileWatcherService.getRecentEvents();
    }
}
