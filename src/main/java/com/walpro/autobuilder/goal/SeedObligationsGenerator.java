package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCallContext;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * T4-CQ: writes {@link SeedObligationsFile} for a root goal that has none, with each line already
 * carrying its {@code rule:} or {@code gate: tests} marker.
 *
 * <p><b>Why generated.</b> Both adopted projects' files were marked by hand, and each marking was a
 * fix for a false completion or a false incompletion: a rule a model would not confirm halted a
 * finished run, and "the whole test suite passes" was put to a model reading source while the test
 * gate had just run it green. An operator adopting a third project would have to know all of that.
 * The local model splits the seed and marks each line in one call, prompt measured on all five
 * targets' seeds on 2026-09-24 before this was wired.
 *
 * <p><b>Still the operator's to correct.</b> The written file is a proposal: it reads back
 * {@code PRISTINE}, and a hand edit reads back {@code EDITED} and is never overwritten. This only
 * writes when there is nothing usable to overwrite -- absent, malformed, or a model-written file
 * whose seed has since moved -- the same table {@link SeedObligationsFile} already decides by.
 *
 * <p><b>The unsafe direction is guarded.</b> A line wrongly marked {@code rule:} leaves closure one
 * question short, which is the false-completion direction; a line wrongly left unmarked adds one.
 * So the prompt says "when unsure, check", an unknown prefix is kept as an ordinary obligation, and
 * a split with no checkable line at all is refused rather than written -- closure would then have
 * nothing to ask.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "autobuilder.obligations.generate", havingValue = "true")
public class SeedObligationsGenerator {

    private static final String CHECK = "check:";

    static final String SYSTEM_PROMPT = """
            You split a software specification into its obligations: one obligation per line, nothing else.

            Each line must stand alone. Carry the subject into it: write "GET /books/{id} returns 404 when the book is absent", never "404 if absent". One claim per line: where a sentence lists several things ("a menu with Start, Pause and Exit"), write one line for each. Use the specification's own words and values; never add a requirement, a value or a name it does not state. Leave out titles, greetings and sentences that only describe the task ("Your task is ...").

            Then mark each line with exactly one prefix:

            "check:" -- the finished result can be examined for it -- by reading the code, running the program, or calling it. Most lines are this.
            "rule:" -- it says HOW the work is to be done, or how to behave while doing it, and no inspection of the finished result could confirm it. Examples: "Work inside the project as it is.", "If a test fails, the test is what is wrong.", "Do not assume a result; observe it."
            "gate: tests" -- it says that the project's own test suite passes. Only a line whose whole claim is that the tests pass gets this prefix, followed by that claim in the specification's words. A line saying what the tests must cover is check:, not gate: tests.

            A line forbidding changes to something that already exists is rule:, because the finished result alone does not show what was changed.

            When unsure between check: and rule:, write check:.

            Output only the lines, each starting with its prefix exactly as written above: "check:", "rule:" or "gate: tests". No numbering, no headings, no blank lines, no commentary.
            """;

    /** What the marker itself means, for a gate line the model wrote without its claim. */
    static final String TEST_GATE_CLAIM = "The project's own test suite passes.";

    private final LLMClient llmClient;
    private final FileService fileService;

    /**
     * Write the obligations file for {@code seedText} when the target has nothing usable. Never
     * throws: without a file the derived split runs exactly as before.
     */
    public void ensureFor(String seedText) {
        if (seedText == null || seedText.isBlank()) return;
        try {
            String existing = fileService.exists(SeedObligationsFile.PATH)
                    ? fileService.readFile(SeedObligationsFile.PATH) : null;
            var state = SeedObligationsFile.read(existing, seedText).state();
            if (state != SeedObligationsFile.State.ABSENT && state != SeedObligationsFile.State.MALFORMED
                    && state != SeedObligationsFile.State.SEED_MOVED) {
                log.info("T4-CQ: {} is {} -- kept, not regenerated", SeedObligationsFile.PATH, state);
                return;
            }
            List<String> lines = parse(ask(seedText));
            if (lines.stream().noneMatch(SeedObligationsGenerator::isCheckable)) {
                log.warn("T4-CQ: the generated split has no checkable line ({} lines) -- not written; "
                        + "the derived split stands", lines.size());
                return;
            }
            fileService.ensureFolderExists("bootstrap");
            fileService.writeFile(SeedObligationsFile.PATH, SeedObligationsFile.render(seedText, lines));
            long rules = lines.stream().filter(SeedRequirements::isRule).count();
            long gates = lines.stream().filter(l -> SeedRequirements.gateOf(l) != null).count();
            log.warn("T4-CQ: wrote {} ({} was {}): {} obligations, {} marked rule:, {} marked gate: tests "
                    + "-- review it; a hand edit is kept", SeedObligationsFile.PATH, SeedObligationsFile.PATH,
                    state, lines.size(), rules, gates);
        } catch (Exception e) {
            log.warn("T4-CQ: could not generate {}: {} -- the derived split stands",
                    SeedObligationsFile.PATH, e.getMessage());
        }
    }

    private String ask(String seedText) {
        List<Message> messages = List.of(
                new Message("system", SYSTEM_PROMPT),
                new Message("user", "SPECIFICATION:\n" + seedText));
        ResponseEntity<ChatResponse> resp;
        try (var purpose = LlmCallContext.scopeIfUnscoped("seed-obligations")) {
            resp = llmClient.ask(messages);
        }
        return resp.getBody() == null ? "" : resp.getBody().getFirstContent();
    }

    /**
     * The model's answer as file lines: {@code check:} dropped (an unmarked line IS a check),
     * {@code rule:} kept, any {@code gate:} line written as {@code gate: tests} -- the one gate
     * there is -- and a line with no known prefix kept as an ordinary obligation.
     */
    static List<String> parse(String answer) {
        List<String> out = new ArrayList<>();
        if (answer == null) return out;
        for (String raw : answer.split("\\R")) {
            String line = raw.strip().replaceFirst("^(?:[-*]|\\d+[.)])\\s+", "");
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("```")) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith(CHECK)) {
                add(out, line.substring(CHECK.length()).strip());
            } else if (SeedRequirements.isRule(line)) {
                String rest = line.substring(SeedRequirements.RULE_MARKER.length()).strip();
                if (!rest.isEmpty()) out.add(SeedRequirements.RULE_MARKER + " " + rest);
            } else if (lower.startsWith(SeedRequirements.GATE_MARKER)) {
                String rest = line.substring(SeedRequirements.GATE_MARKER.length()).strip();
                if (rest.toLowerCase(Locale.ROOT).startsWith(SeedRequirements.GATE_TESTS)) {
                    rest = rest.substring(SeedRequirements.GATE_TESTS.length()).strip();
                }
                out.add(SeedRequirements.GATE_MARKER + " " + SeedRequirements.GATE_TESTS + " "
                        + (rest.isEmpty() ? TEST_GATE_CLAIM : rest));
            } else {
                add(out, line);
            }
        }
        return out;
    }

    private static void add(List<String> out, String line) {
        if (!line.isEmpty()) out.add(line);
    }

    private static boolean isCheckable(String line) {
        return !SeedRequirements.isRule(line) && SeedRequirements.gateOf(line) == null;
    }
}
