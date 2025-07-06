package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.service.AutobuilderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/autobuilder")
@RequiredArgsConstructor
public class AutobuilderController {

    private final AutobuilderService autobuilderService;
    
    @PostMapping("/evolve")
    public ResponseEntity<String> evolveProjectManually() {
        autobuilderService.runAutobuilderCycle();
        return ResponseEntity.ok("Evolution cycle triggered.");
    }
}
