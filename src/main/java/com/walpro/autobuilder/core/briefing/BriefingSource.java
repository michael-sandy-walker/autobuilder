package com.walpro.autobuilder.core.briefing;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * T4-EA: the target's briefing, {@value #DIR}/*.md -- read when its files change, and selected per prompt.
 *
 * <p>A document named {@value #ALWAYS} (the conventions) is shown in every prompt that carries a briefing,
 * within the budget; every other document contributes the sections that match what the prompt is about. No
 * briefing directory, no block: every existing target behaves as before.
 *
 * <p>The briefing is never read as requirements: the obligations and seed-status readers read
 * {@code bootstrap/seed.txt} and its obligations file only.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE, note = "a cache of bootstrap/briefing, re-read when its files change")
public class BriefingSource {

    public static final String DIR = "bootstrap/briefing";
    static final String ALWAYS = "conventions.md";

    @Autowired(required = false)
    private FileService fileService;

    /** Whether the target's briefing is added to prompts as background. Off only to MEASURE the briefing (T4-EA's A/B): the same toy case, with and without it. */
    @Value("${autobuilder.briefing.enabled:true}")
    private boolean enabled = true;

    /** Share of the prompt's free headroom a briefing may take, and its ceiling in tokens. */
    @Value("${autobuilder.briefing.headroom-share:0.25}")
    private double headroomShare = 0.25;
    // The most tokens a briefing block may take in a prompt, whatever headroom is left.
    @Value("${autobuilder.briefing.max-tokens:2000}")
    private int maxTokens = 2000;

    private String cachedStamp;
    private Briefing cached = new Briefing(List.of());

    /** The briefing block for a prompt about {@code about}, given the prompt's free headroom; null when none. */
    public String block(String about, int headroomTokens) {
        if (!enabled) return null;
        Briefing b = briefing();
        if (b.isEmpty()) return null;
        int budget = (int) Math.min(maxTokens, Math.max(0, headroomTokens) * headroomShare);
        String block = b.promptBlock(about, budget);
        if (block != null) log.debug("T4-EA: briefing block of ~{} token(s) for a prompt about {}", block.length() / 4,
                about == null ? "-" : about.substring(0, Math.min(80, about.length())));
        return block;
    }

    /** What a prompt of this cycle is about: the goal, the current subgoal and its criteria. */
    public static String aboutOf(CycleContextData ctx) {
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder();
        if (ctx.currentGoalDescription() != null) sb.append(ctx.currentGoalDescription()).append('\n');
        if (ctx.currentSubGoalDescription() != null) sb.append(ctx.currentSubGoalDescription()).append('\n');
        if (ctx.currentSubGoalCriteria() != null) ctx.currentSubGoalCriteria().forEach(c -> sb.append(c).append('\n'));
        return sb.toString();
    }

    synchronized Briefing briefing() {
        if (fileService == null) return cached;
        try {
            if (!fileService.exists(DIR)) return cached = new Briefing(List.of());
            Path dir = fileService.resolveSecurePath(DIR);
            List<Path> docs;
            try (Stream<Path> s = Files.list(dir)) {
                docs = s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            }
            StringBuilder stamp = new StringBuilder();
            for (Path p : docs) stamp.append(p.getFileName()).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(';');
            if (stamp.toString().equals(cachedStamp)) return cached;
            List<Briefing.Section> sections = new ArrayList<>();
            for (Path p : docs) {
                String name = p.getFileName().toString();
                sections.addAll(Briefing.parse(name.replaceFirst("\\.md$", ""), Files.readString(p), name.equals(ALWAYS)));
            }
            cached = new Briefing(sections);
            cachedStamp = stamp.toString();
            log.info("T4-EA: briefing read -- {} document(s), {} section(s)", docs.size(), sections.size());
        } catch (Exception e) {
            log.debug("T4-EA: briefing unreadable: {}", e.getMessage());
        }
        return cached;
    }
}
