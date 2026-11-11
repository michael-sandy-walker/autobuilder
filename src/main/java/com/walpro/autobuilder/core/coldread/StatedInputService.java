package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.goal.GoalPlannerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * T4-ES: a project that has a goal is given its NEXT input in words -- what it should (also) do, one statement a line.
 *
 * <p>A project keeps taking input; the seed is only the first. Until now a later input was always another project
 * (its code, goals and criteria joining); a requirement someone simply states had a ledger entry
 * ({@link GoalPlannerService#addInput}) and no way in, no goal, and a finished project stayed finished.
 *
 * <p>The input lands at once -- it moves no file, so there is nothing to look over before keeping it: it becomes the
 * project's next ledger entry (its obligations written like any input's), and each of its lines is first put to the
 * code ({@link ReverseEngineeringService#checkStated}). What the project already does becomes a finished goal with the
 * checks that showed it; the rest is open work under the root, which reopens for it.
 */
@Slf4j
@Service
public class StatedInputService {

    private final GoalPlannerService goals;
    private final ReverseEngineeringService service;
    private final ReverseEngineeringConfirmation confirmation;
    private final Path base;

    public StatedInputService(GoalPlannerService goals, ReverseEngineeringService service,
                              ReverseEngineeringConfirmation confirmation, @Value("${file.base-path}") String fileBasePath) {
        this.goals = goals;
        this.service = service;
        this.confirmation = confirmation;
        this.base = Path.of(fileBasePath);
    }

    /** What checking the input's lines found, kept in the input's own folder under {@code meta/inputs/<id>/}. */
    public static final String STATED_FILE = "stated.json";

    /** One line of the input and what became of it. */
    public record Line(String line, boolean held, int criteria, String why) {
    }

    /** What taking the input made: the ledger entry, each line's outcome, and the goals. */
    public record Report(String inputId, List<Line> lines, List<String> heldGoals, List<String> openGoals) {
    }

    /**
     * The statements of an input: one per non-blank line, a list marker in front taken off. A line starting
     * {@code rule:} says how the work is done, not something the project does -- it is no statement to check or build
     * (the input's obligations keep it).
     */
    public static List<String> statements(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\\R")) {
            String l = raw.strip().replaceFirst("^(?:[-*+]|\\d+[.)])\\s+", "").strip();
            if (l.isEmpty() || l.regionMatches(true, 0, ObservedRules.MARKER, 0, ObservedRules.MARKER.length())) continue;
            if (!out.contains(l)) out.add(l);
        }
        return out;
    }

    public Report take(String text, String by) throws IOException {
        if (goals.getRootGoal() == null) {
            throw new IllegalStateException("this project has no goal yet: give it its first goal, then add to it");
        }
        List<String> statements = statements(text);
        if (statements.isEmpty()) throw new IllegalArgumentException("the input states nothing: write what the project should do, one statement a line");
        Path tree = base.resolve("output").toAbsolutePath().normalize();
        var input = goals.addInput(text, by == null || by.isBlank() ? "operator" : by);
        Path work = Path.of(System.getProperty("java.io.tmpdir"), "autobuilder-reverse-engineering",
                base.toAbsolutePath().normalize().getFileName().toString());
        // Claim ids name stored checks and claim-test classes for the project's whole life; a number from the clock
        // keeps this input's apart from every earlier one's without a counter to keep.
        int first = 10_000 + (int) ((System.currentTimeMillis() / 1000) % 80_000);
        var checked = service.checkStated(tree, work, null, List.of(), first, statements,
                stage -> log.info("T4-ES: {}", stage));
        var landed = confirmation.landStated(input.id(), checked);
        // With the input it belongs to, beside its obligations.
        ReverseEngineeringService.keepStated(base.resolve("meta/inputs").resolve(input.id()).resolve(STATED_FILE), checked);
        List<Line> lines = checked.stream().map(c -> new Line(c.line(), c.holds(), c.claims().size(), c.why())).toList();
        return new Report(input.id(), lines, landed.held(), landed.open());
    }
}
