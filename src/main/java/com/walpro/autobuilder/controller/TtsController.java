package com.walpro.autobuilder.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/tts")
public class TtsController {

    private static final int MAX_CHARS = 600;
    private final TtsService tts;

    public TtsController(TtsService tts) {
        this.tts = tts;
    }

    @Operation(summary = "Synthesize speech (WAV)", description = "Offline neural TTS via Piper; returns audio/wav.")
    @GetMapping("/synthesize")
    public ResponseEntity<byte[]> synthesize(@RequestParam String text) throws Exception {
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String trimmed = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        try {
            byte[] wav = tts.synthesizeWav(trimmed);
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("audio/wav"))
                    .header("Cache-Control", "no-store")
                    .body(wav);
        } catch (IllegalStateException notReady) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
