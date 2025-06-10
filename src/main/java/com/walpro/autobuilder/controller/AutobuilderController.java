package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.service.AutobuilderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/autobuilder")
@RequiredArgsConstructor
public class AutobuilderController {

    private final AutobuilderService autobuilderService;

    @Operation(
        summary = "Manually trigger the Autobuilder",
        description = "Runs the Autobuilder manually by draining recent file change events and processing them via LLM."
    )
    @ApiResponse(responseCode = "200", description = "Autobuilder executed successfully")
    @PostMapping("/run")
    public ResponseEntity<String> runAutobuilder() {
        try {
            autobuilderService.processAutonomousChanges();
            return ResponseEntity.ok("Autobuilder run triggered.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
    
    @PostMapping("/evolve")
    public ResponseEntity<String> evolveProjectManually() {
        autobuilderService.evolveProject();
        return ResponseEntity.ok("Evolution cycle triggered.");
    }
}
