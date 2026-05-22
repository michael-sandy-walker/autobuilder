package com.walpro.autobuilder.goal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every external (non-{@link GoalPlannerService}) site that retires a goal must route through
 * {@link GoalPlannerService#transitionGoal(String, GoalStatus, String)} — the deprecated
 * {@code markGoalCompleted} / {@code markGoalGivenUp} adapters are package-private and raw
 * {@code setStatus(GoalStatus.COMPLETED|GIVEN_UP|SUBSUMED)} on {@link GoalEntity} is
 * forbidden in production code outside the goal package. This is what keeps the status flip
 * paired with the appropriate ledger write; without it the two can drift apart at any future
 * call site.
 */
class GoalTransitionApiContractTest {

    private static final Pattern SET_TERMINAL_STATUS = Pattern.compile(
            "\\.setStatus\\(\\s*GoalStatus\\.(COMPLETED|GIVEN_UP|SUBSUMED)\\b");

    private static final Pattern MARK_INSTANCE = Pattern.compile(
            "\\.(markCompleted|markGivenUp)\\(\\s*\\)");

    private static final Pattern MARK_GOAL_SERVICE = Pattern.compile(
            "\\.(markGoalCompleted|markGoalGivenUp)\\(");

    @Test
    void productionCodeOutsideGoalPackageMustUseTransitionGoal() throws IOException {
        Path mainJava = Path.of("src/main/java");
        assertThat(Files.isDirectory(mainJava)).isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainJava)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/goal/")) // planner owns the transition API
                    .forEach(p -> {
                        try {
                            scanFile(p, offenders);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        assertThat(offenders)
                .as("Every external terminal-status transition must call "
                        + "GoalPlannerService.transitionGoal(...) so banking stays paired with the status flip.")
                .isEmpty();
    }

    private static void scanFile(Path p, List<String> offenders) throws IOException {
        List<String> lines = Files.readAllLines(p);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            check(p, i, line, SET_TERMINAL_STATUS, offenders);
            check(p, i, line, MARK_INSTANCE, offenders);
            check(p, i, line, MARK_GOAL_SERVICE, offenders);
        }
    }

    private static void check(Path p, int idx, String line, Pattern pat, List<String> offenders) {
        Matcher m = pat.matcher(line);
        if (m.find()) {
            offenders.add(p + ":" + (idx + 1) + ": " + line.strip());
        }
    }
}
