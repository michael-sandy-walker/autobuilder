package com.walpro.autobuilder.service;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FingerprintService {

    public String computeFingerprint(List<FileMutation> files) {
        return files.stream()
                .sorted(Comparator.comparing(FileMutation::path))
                .map(f -> f.path() + ":" + normalize(f.content()))
                .collect(Collectors.joining("||"));
    }

    public String normalize(String content) {
        return content.replaceAll("\\s+", ""); // crude but effective early signal
    }
}
