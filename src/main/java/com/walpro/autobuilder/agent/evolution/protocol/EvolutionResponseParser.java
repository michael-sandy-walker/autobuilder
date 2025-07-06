package com.walpro.autobuilder.agent.evolution.protocol;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EvolutionResponseParser {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```\\w+ \\[(.+?)\\](.+?)```");
    private static final Pattern DELETE_PATTERN = Pattern.compile("#Delete \\[(.+?)]");

    public EvolutionOutcome parse(String content) {
        List<FileMutation> changedFiles = new ArrayList<>();
        List<String> deletedFiles = new ArrayList<>();

        Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(content);
        while (codeMatcher.find()) {
            String path = codeMatcher.group(1).trim();
            String fileContent = codeMatcher.group(2).trim();
            changedFiles.add(new FileMutation(path, fileContent));
        }

        Matcher deleteMatcher = DELETE_PATTERN.matcher(content);
        while (deleteMatcher.find()) {
            deletedFiles.add(deleteMatcher.group(1).trim());
        }

        String summary = "Parsed %d changed file(s), %d deleted file(s)."
                .formatted(changedFiles.size(), deletedFiles.size());

        return new EvolutionOutcome(changedFiles, deletedFiles, summary);
    }
}
