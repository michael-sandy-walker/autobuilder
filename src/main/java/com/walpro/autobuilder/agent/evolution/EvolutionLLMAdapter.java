package com.walpro.autobuilder.agent.evolution;

import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.graph.evolution.EvolutionActionRegistrar;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphFacade;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.protocol.evolution.EvolutionProtocolContract;
import com.walpro.autobuilder.protocol.evolution.EditHunks;
import com.walpro.autobuilder.protocol.evolution.EvolutionResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.build.PackageConsolidationService;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionLLMAdapter {

    private final LLMClient llmClient;
    private final EvolutionGraphFacade graphFacade;
    private final LLMAgentProtocolInvoker llmAgentProtocolInvoker;
    private final EvolutionActionRegistrar evolutionActionRegistrar;
    private final SymbolGroundingService groundingService;
    private final PackageConsolidationService packageConsolidationService;
    private final com.walpro.autobuilder.protocol.PromptTokenBudget promptTokenBudget;

    private final EvolutionResponseParser parser = new EvolutionResponseParser(); // not injected

    /** T4-CQ: optional — reads the served prefix from the tree; absent in narrow unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.coldread.ColdReadService coldReadService;

    private String servedPrefixOf(CycleContextData ctx) {
        if (coldReadService == null || ctx == null || ctx.outputPath() == null) return null;
        try {
            return coldReadService.servedPrefix(java.nio.file.Path.of(ctx.outputPath())).orElse(null);
        } catch (RuntimeException e) {
            return null; // a prompt is never withheld over a fact that could not be read
        }
    }

    /** T6-T: the test gate and the goal tree, to state the test gate's work item as of now; optional in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.walpro.autobuilder.core.build.TestRunGate> testRunGate;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.walpro.autobuilder.goal.GoalPlannerService> goalPlannerService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.walpro.autobuilder.goal.DeclaredTestSuite> declaredTestSuite;

    private CycleContextData withTestRunNow(CycleContextData ctx) {
        var gate = testRunGate == null ? null : testRunGate.getIfAvailable();
        var goals = goalPlannerService == null ? null : goalPlannerService.getIfAvailable();
        if (gate == null || goals == null) return ctx;
        String spec = goals.specification();
        boolean required = spec != null
                && com.walpro.autobuilder.core.build.TestRunGate.specRequiresTests(spec);
        boolean[] ran = {false};
        CycleContextData out = TestWorkItemNow.restate(ctx,
                id -> goals.getGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY),
                () -> {
                    ran[0] = true;
                    return gate.runOnTree(ctx.cycleName() + "-evolution-tests", required);
                });
        // T6-T: on a declared suite the tree's test state is the baseline every candidate is compared with
        // (TestRegressionHeuristic, the test-progress acceptance); while it is unknown -- at the start, after a
        // merge with no test run -- the tree is run once, before any candidate is on disk.
        var declared = declaredTestSuite == null ? null : declaredTestSuite.getIfAvailable();
        if (!ran[0] && declared != null && declared.isDeclared() && gate.isLive()
                && gate.treeTests() == com.walpro.autobuilder.core.build.TestRunGate.TreeTests.UNKNOWN) {
            try {
                gate.runOnTree(ctx.cycleName() + "-tree-tests", required);
            } catch (RuntimeException e) {
                log.debug("T6-T: the tree's test run could not be made: {}", e.getMessage());
            }
        }
        return out;
    }

    /** T4-CQ: operator-declared paths no writer may touch; optional in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.build.ProtectedPaths protectedPaths;

    /** T4-CQ: values the code was observed to return, named in the prompt; optional in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.build.ObservedBehaviour observedBehaviour;

    /** T4-EA: the target's briefing, when it carries one; optional in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.briefing.BriefingSource briefingSource;

    /** T4-DY slice B's replay switch: the subgoal source block, on unless a replay takes it away. */
    @org.springframework.beans.factory.annotation.Value("${autobuilder.evolution.subgoal-source-block:true}")
    void setSubgoalSourceBlock(boolean on) {
        com.walpro.autobuilder.protocol.evolution.SubgoalSourcePromptBlock.setEnabled(on);
    }

    /** T4-DY: answers to what kept failing, asked before this prompt is built; optional in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.stuck.StuckLayer stuckLayer;

    /** T4-EE: one question of the run's log, offered on a retry; optional in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.investigate.LogConsultation logConsultation;

    /** T4-DY: the project's classpath, for the dependency-API block; optional in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.stuck.ProjectTypeAuthority projectTypeAuthority;

    public CycleContextData evolve(CycleContextData context) throws IOException {
        return evolve(context, false);
    }

    public CycleContextData evolve(CycleContextData context, boolean recombined) throws IOException {
        // T1-EM: re-ground [UNDEFINED]/[NOT YET PRESENT] markers in goal descriptions against
        // current output state before rendering — same pattern as PlannerLLMAdapter /
        // AnalysisLLMAdapter. Without this, the evolution prompt can carry stale markers and
        // encourage re-creating files that already exist on disk.
        CycleContextData refreshed = withTestRunNow(groundingService.refreshGoalAnnotations(context));
        var result = refreshed.evaluationInterpretation();
        String systemPrompt = EvolutionProtocolContract.getSystemPrompt();
        java.util.function.Predicate<String> isProtected = protectedPaths == null ? path -> false
                : protectedPaths::isProtected;
        var gate = testRunGate == null ? null : testRunGate.getIfAvailable();
        boolean treeTestsRan = gate != null
                && gate.treeTests() == com.walpro.autobuilder.core.build.TestRunGate.TreeTests.RAN;
        String userPrompt = EvolutionProtocolContract.getUserPrompt(refreshed, result, recombined,
                promptTokenBudget.promptHeadroomTokens(), isProtected, treeTestsRan,
                stuckLayer == null ? java.util.Map.of() : stuckLayer.failingPath());
        // T4-CQ: the paths the operator protected, and every write refused since the last prompt --
        // a refusal the model never hears about is a move it will make again.
        if (protectedPaths != null) {
            String block = protectedPaths.promptBlock();
            if (block != null) userPrompt = userPrompt + "\n\n" + block;
        }
        // T4-DY: a failure that kept repeating is a question about the code; its answer, read from the
        // sources now, is in the move it is about.
        if (stuckLayer != null) {
            String answers = stuckLayer.promptBlock(refreshed, Math.max(0, promptTokenBudget.promptHeadroomTokens()
                    - com.walpro.autobuilder.protocol.PromptTokenBudget.estimateTokens(userPrompt)) / 2);
            if (answers != null) userPrompt = userPrompt + "\n\n" + answers;
        }
        // T4-DY: the API of the dependencies this work uses, read from the project's jars, before the model writes.
        if (projectTypeAuthority != null && refreshed.outputPath() != null) {
            var classpath = projectTypeAuthority.classpath(java.nio.file.Path.of(refreshed.outputPath()));
            if (classpath.isPresent()) {
                List<String> sources = com.walpro.autobuilder.core.build.DependencyApi.sourcesNeeded(
                        refreshed.previousOutput(), stuckLayer == null ? java.util.Map.of() : stuckLayer.lastFailedChange(),
                        isProtected);
                String api = com.walpro.autobuilder.core.build.DependencyApi.block(sources,
                        refreshed.currentGoalDescription() + "\n" + refreshed.currentSubGoalDescription(),
                        classpath.get().jars(), classpath.get().loader(),
                        Math.max(0, promptTokenBudget.promptHeadroomTokens()
                                - com.walpro.autobuilder.protocol.PromptTokenBudget.estimateTokens(userPrompt)) / 2);
                if (api != null) userPrompt = userPrompt + "\n\n" + api;
            }
        }
        // T4-EA: the parts of the target's briefing this subgoal is about -- background, not requirements.
        if (briefingSource != null) {
            String briefing = briefingSource.block(com.walpro.autobuilder.core.briefing.BriefingSource.aboutOf(refreshed),
                    Math.max(0, promptTokenBudget.promptHeadroomTokens()
                            - com.walpro.autobuilder.protocol.PromptTokenBudget.estimateTokens(userPrompt)));
            if (briefing != null) userPrompt = userPrompt + "\n\n" + briefing;
        }
        // T4-CQ: what the code was seen to return, for as long as that code is unchanged.
        if (observedBehaviour != null && refreshed.outputPath() != null) {
            String observed = observedBehaviour.promptBlock(
                    com.walpro.autobuilder.core.build.ObservedBehaviour.mainDigestOnDisk(
                            java.nio.file.Path.of(refreshed.outputPath())));
            if (observed != null) userPrompt = userPrompt + "\n\n" + observed;
        }
        // T4-CQ: where the routes are really answered, when the tree's own configuration says.
        userPrompt = com.walpro.autobuilder.protocol.evolution.ServedPrefixPromptBlock.appendTo(
                userPrompt, servedPrefixOf(refreshed));

        // T6-T: a file rewritten whole to no effect on the failing tests is changed by edits next (RewriteBrake).
        String editsFirst = editsFirstBlock(refreshed);
        if (editsFirst != null) userPrompt = userPrompt + "\n\n" + editsFirst;

        // T4-EE: retrying rejected work, the model may first ask one question of the run's log.
        boolean retrying = refreshed.rejectionFeedback() != null && !refreshed.rejectionFeedback().isEmpty();
        String logOffer = retrying && logConsultation != null ? logConsultation.offerBlock() : null;
        if (logOffer != null) userPrompt = userPrompt + "\n\n" + logOffer;
        java.util.function.Function<String, String> consult = logOffer == null ? null
                : raw -> logConsultation.answer(raw, "evolution", refreshed.cycleName(),
                        refreshed.outputPath() == null ? null : java.nio.file.Path.of(refreshed.outputPath()));

        LLMResponseResult<EvolutionOutcome> response = llmAgentProtocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                raw -> EvolutionProtocolContract.isValidResponse(raw)
                        && EditHunks.apply(com.walpro.autobuilder.protocol.evolution.EvolutionResponseParser.normalizeFileHeaders(raw),
                                currentTree(refreshed)).problems().isEmpty()
                        && whatItChangesNot(raw, refreshed, false) == null,
                raw -> parser.parse(raw, currentTree(refreshed)),
                raw -> {
                    // T4-EB: an edit whose SEARCH text does not occur exactly once is asked again, with where.
                    String format = EvolutionProtocolContract.describeMismatch(raw);
                    if (format != null) return format;
                    List<String> problems = EditHunks.apply(
                            com.walpro.autobuilder.protocol.evolution.EvolutionResponseParser.normalizeFileHeaders(raw),
                            currentTree(refreshed)).problems();
                    if (!problems.isEmpty()) return "an edit could not be applied: " + String.join("; ", problems);
                    return whatItChangesNot(raw, refreshed, true);
                },
                consult
        );

        EvolutionOutcome outcome;

        if (response.success()) {
            outcome = response.outcome().withRecombined(recombined); // TODO: let the LLM return this flag?

            // T4-BL: consolidate fragmenting writes to their type's existing package before
            // anything downstream (graph records, probe, merge) sees the outcome.
            outcome = packageConsolidationService.consolidate(
                    outcome, refreshed.outputPath(), refreshed.cycleName());
            // T4-CQ: and with every declaration a member-protected file had at adoption kept as it
            // was -- here, before the graph, the probe or the merge sees the outcome.
            outcome = preserveAdopted(outcome);

            evolutionActionRegistrar.registerEvolution("StaticEvolutionAgent", context, outcome);
            evolveGraph(context, outcome);
        } else {
            log.warn("EvolutionAgent received invalid LLM response. Reason: {}. Content:\n{}",
                    response.failureReason(),
                    response.rawContent() != null ? response.rawContent() : "[null]"
            );

            outcome = new EvolutionOutcome(
                    List.of(),
                    List.of(),
                    EvolutionOutcome.INVALID_PREFIX + response.failureReason(),
                    response.rawContent(),
                    recombined
            );
        }

        return context
                .withEvolutionOutcome(outcome);
    }

    /** T6-T: what the brake holds; optional in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RewriteBrake rewriteBrake;

    /**
     * T6-T: why an answer that is otherwise well-formed is asked again, or null when it is not. Two reasons, both read
     * against the files as they are now:
     * <ul>
     *   <li>Every file it writes says exactly what the file says already -- it changes nothing. Ladder-rows run
     *       2026-10-14_18-10-40: twelve of 43 answers were the class as it stood, one of them ten times over 90 minutes;
     *       each cost a call, some a test run, and with the prompt nearly the same each time the answer did not move.
     *       Saying so changes the next request, which is what breaks the repetition.</li>
     *   <li>It rewrites a file {@link RewriteBrake} holds -- one rewritten before to no effect on the failing tests.</li>
     * </ul>
     * {@code counted}: the answer is being asked again for it (the brake counts that), not only checked.
     */
    String whatItChangesNot(String raw, CycleContextData ctx, boolean counted) {
        EvolutionOutcome parsed;
        try {
            parsed = parser.parse(raw, currentTree(ctx));
        } catch (RuntimeException e) {
            return null;
        }
        if (parsed == null || parsed.noOp()) return null;
        java.util.Map<String, String> tree = currentTree(ctx);
        List<com.walpro.autobuilder.agent.evolution.model.FileMutation> files =
                parsed.addedOrChangedFiles() == null ? List.of() : parsed.addedOrChangedFiles();
        boolean deletes = parsed.deletedFiles() != null && !parsed.deletedFiles().isEmpty();
        if (!files.isEmpty() && !deletes && files.stream().allMatch(f -> f != null && f.path() != null
                && tree.get(f.path()) != null && tree.get(f.path()).strip().equals(f.content() == null ? "" : f.content().strip()))) {
            return "it is identical to " + String.join(", ", files.stream().map(f -> f.path()).toList())
                    + " as the file stands now, so it changes nothing. Write the change the failing tests need -- an"
                    + " edit block of the part to change -- or answer #NoDiff if nothing needs to change";
        }
        if (rewriteBrake != null) {
            for (var f : files) {
                if (f == null || f.path() == null) continue;
                if (rewriteBrake.holds(f.path(), tree.get(f.path()), f.content())) {
                    if (counted) rewriteBrake.refused(f.path());
                    return "it rewrites " + f.path() + " again, and its last rewrite left the failing tests as they"
                            + " were. Change it with edit blocks -- each SEARCH part copied from the file as it stands --"
                            + " in the parts the failing tests run through";
                }
            }
        }
        return null;
    }

    /** T6-T: the prompt block for the files {@link RewriteBrake} holds that this tree has; null when none. */
    private String editsFirstBlock(CycleContextData ctx) {
        if (rewriteBrake == null) return null;
        java.util.Map<String, String> tree = currentTree(ctx);
        List<String> held = rewriteBrake.held().stream().filter(tree::containsKey).sorted().toList();
        if (held.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("--- CHANGE THESE FILES BY EDITS ---\n")
                .append("The last new version of each of these files replaced much of it and left the failing tests as"
                        + " they were. Change them with edit blocks this time, in the parts the failing tests run through;"
                        + " a version that replaces much of one again is asked again, until a change to it makes the"
                        + " tests better:\n");
        for (String path : held) {
            String name = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
            List<String> methods = stuckLayer == null ? List.of() : stuckLayer.methodsOnFailingPath(name);
            sb.append("  - ").append(path)
                    .append(methods.isEmpty() ? "" : " -- the failing tests run through " + String.join("(), ", methods) + "()")
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** T4-EB: the files as they are now, which an edit block's SEARCH text is matched against. */
    private static java.util.Map<String, String> currentTree(CycleContextData ctx) {
        return ctx == null || ctx.previousOutput() == null ? java.util.Map.of() : ctx.previousOutput();
    }

    private EvolutionOutcome preserveAdopted(EvolutionOutcome outcome) {
        if (protectedPaths == null || outcome == null) return outcome;
        List<com.walpro.autobuilder.agent.evolution.model.FileMutation> files = outcome.addedOrChangedFiles() == null ? List.of()
                : outcome.addedOrChangedFiles().stream().map(f -> f == null || f.path() == null ? f
                        : new com.walpro.autobuilder.agent.evolution.model.FileMutation(f.path(),
                                protectedPaths.preserve("evolution", f.path(), f.content()))).toList();
        List<String> deleted = outcome.deletedFiles() == null ? List.of()
                : outcome.deletedFiles().stream().filter(d -> !protectedPaths.refuseDelete("evolution", d)).toList();
        return outcome.withAddedOrChangedFiles(files).withDeletedFiles(deleted);
    }

    public CycleContextData evolveDivergent(CycleContextData ctx) {
        try {
            return evolve(ctx, true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void evolveGraph(CycleContextData context, EvolutionOutcome outcome) {
        graphFacade.recordEvolution(context, outcome);
    }

}
