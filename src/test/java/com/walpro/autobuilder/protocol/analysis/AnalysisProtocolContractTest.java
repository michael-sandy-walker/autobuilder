package com.walpro.autobuilder.protocol.analysis;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisProtocolContractTest {

    @Test
    void getUserPrompt_includesPackageConstraint_whenBasePackageSet() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c-1")
                .currentGoalDescription("Library API")
                .currentSubGoalDescription("Create Loan entity")
                .establishedBasePackage("com.example.demo")
                .build();

        String prompt = AnalysisProtocolContract.getUserPrompt(ctx);

        // The block names the package and the exact path prefix every Criteria path must start with.
        assertThat(prompt).contains("--- PACKAGE CONSTRAINT ---");
        assertThat(prompt).contains("`com.example.demo`");
        assertThat(prompt).contains("src/main/java/com/example/demo/");
    }

    @Test
    void getUserPrompt_rendersSignalNames_notStrengths() {
        // The SIGNALS block renders present signal NAMES only. 7a687f5 rendered NAME=strength
        // (so the LLM could judge the #InjectionThrottle "significant strength" condition), but
        // that regressed generated-code quality — carriers saturate at 3.00, the LLM over-read
        // "significant strength" and over-throttled injection. Reverted to e0b5bff names-only.
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c-1")
                .currentGoalDescription("Library API")
                .currentSubGoalDescription("Create Loan entity")
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                        com.walpro.autobuilder.core.lifecycle.BasicSignals.REPETITION_DETECTED, 1.0, "test",
                        com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.15)))
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                        com.walpro.autobuilder.core.lifecycle.BasicSignals.STAGNATION, 0.7, "test",
                        com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(0.7, 0.20)));

        String prompt = AnalysisProtocolContract.getUserPrompt(ctx);

        assertThat(prompt).contains("REPETITION_DETECTED");
        // names only (no =strength): 7a687f5 strength-render regressed injection throttling, reverted
        assertThat(prompt).doesNotContain("REPETITION_DETECTED=");
        assertThat(prompt).contains("STAGNATION");
        assertThat(prompt).doesNotContain("STAGNATION=");
    }

    @Test
    void getUserPrompt_signalsBlockFallsBackToNone_whenNoSignals() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c-1")
                .currentGoalDescription("Library API")
                .currentSubGoalDescription("Create Loan entity")
                .build();

        String prompt = AnalysisProtocolContract.getUserPrompt(ctx);

        assertThat(prompt).contains("--- SIGNALS ---\n(none)");
    }

    @Test
    void getUserPrompt_omitsPackageConstraint_whenBasePackageMissing() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c-1")
                .currentGoalDescription("Library API")
                .currentSubGoalDescription("Create Loan entity")
                .build();

        String prompt = AnalysisProtocolContract.getUserPrompt(ctx);
        assertThat(prompt).doesNotContain("--- PACKAGE CONSTRAINT ---");
    }

    @Test
    void packageConstraintBlock_convertsDottedPackageToSlashPath() {
        CycleContextData ctx = CycleContextData.builder()
                .establishedBasePackage("com.acme.foo.bar")
                .build();
        String block = AnalysisProtocolContract.packageConstraintBlock(ctx);
        assertThat(block).contains("src/main/java/com/acme/foo/bar/");
    }

    @Test
    void packageConstraintBlock_includesPackageCoherenceRule() {
        // Regression for 2026-05-19 21:44 run where every controller and service merged under
        // <base>.model/. The block must now name the principle (distinct responsibilities →
        // distinct sub-packages, consistent within the project) anchored to the project's actual
        // base package, without prescribing a framework-specific tree shape.
        CycleContextData ctx = CycleContextData.builder()
                .establishedBasePackage("com.example.demo")
                .build();
        String block = AnalysisProtocolContract.packageConstraintBlock(ctx);
        assertThat(block).contains("PACKAGE COHERENCE RULE");
        assertThat(block).contains("com.example.demo");
        assertThat(block).contains("src/main/java/com/example/demo/");
    }

    @Test
    void packageConstraintBlock_doesNotPrescribeFrameworkSpecificSubpackages() {
        // The block intentionally avoids naming `controller/`, `service/`, `repository/`, etc. —
        // those names only apply to Spring-style REST projects. Library projects, CLIs, batch
        // jobs etc. should be free to pick their own taxonomy. Lock this in so we don't backslide
        // into prescribing a layout in future edits.
        CycleContextData ctx = CycleContextData.builder()
                .establishedBasePackage("com.example.demo")
                .build();
        String block = AnalysisProtocolContract.packageConstraintBlock(ctx);
        assertThat(block).doesNotContain("controller/");
        assertThat(block).doesNotContain("service/");
        assertThat(block).doesNotContain("repository/");
        assertThat(block).doesNotContain("@RestController");
        assertThat(block).doesNotContain("JpaRepository");
    }

    @Test
    void packageConstraintBlock_returnsEmpty_onBlankBasePackage() {
        assertThat(AnalysisProtocolContract.packageConstraintBlock(
                CycleContextData.builder().establishedBasePackage("").build())).isEmpty();
        assertThat(AnalysisProtocolContract.packageConstraintBlock(
                CycleContextData.builder().establishedBasePackage(null).build())).isEmpty();
        assertThat(AnalysisProtocolContract.packageConstraintBlock(null)).isEmpty();
    }

    @Test
    void getUserPrompt_stillValidWithConstraint() {
        // The constraint must not break the response-format requirement encoded in isValidResponse —
        // a self-check that the prompt's required-section labels still appear.
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalDescription("g").currentSubGoalDescription("s")
                .establishedBasePackage("com.example.demo")
                .build();
        String prompt = AnalysisProtocolContract.getUserPrompt(ctx);
        assertThat(prompt)
                .contains("Findings:")
                .contains("Hypotheses:")
                .contains("ProposedExecutableSubGoals:")
                .contains("Message:")
                .contains("GoalKind:")
                .contains("PossiblyComplete:");
    }
}
