package com.walpro.autobuilder.core.build;

import java.util.List;
import java.util.Map;

/**
 * T4-CQ: what the project's own test run reported, test by test, read from the runner's own
 * per-class reports rather than from the console tail.
 *
 * @param byClass every test case per test class (fully qualified), in report order
 */
public record TestRunReport(int run, int failures, int errors, int skipped,
                            List<Failure> failing, Map<String, List<Case>> byClass) {

    public enum Outcome { PASSED, FAILED, ERROR, SKIPPED }

    public record Case(String method, Outcome outcome) {}

    /**
     * @param input the row a failing parameterized invocation ran with, as its test source writes it
     *              ({@link ParameterizedCaseInput}); null when the test is not one or the row is not literal
     */
    public record Failure(String className, String method, Outcome outcome, String message, String input) {
        public Failure(String className, String method, Outcome outcome, String message) {
            this(className, method, outcome, message, null);
        }
    }

    public boolean passes() {
        return run > 0 && failures == 0 && errors == 0;
    }
}
