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

    /** T6-T: stubs the specification's missing surface once, before the first candidate. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.walpro.autobuilder.core.build.SkeletonFirst> skeletonFirst;

    private CycleContextData withTestRunNow(CycleContextData ctx) {
        var gate = testRunGate == null ? null : testRunGate.getIfAvailable();
        var goals = goalPlannerService == null ? null : goalPlannerService.getIfAvailable();
        if (gate == null || goals == null) return ctx;
        var root = goals.getRootGoal();
        boolean required = root != null
                && com.walpro.autobuilder.core.build.TestRunGate.specRequiresTests(root.getDescription());
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
        // T6-T: skeleton first -- the operator's tests do not compile on the tree: stub what they need from the
        // compiler's errors before any candidate, then measure the tree again (its baseline changed)
        var skeleton = skeletonFirst == null ? null : skeletonFirst.getIfAvailable();
        if (skeleton != null && !skeleton.attempted() && declared != null && declared.isDeclared() && gate.isLive()
                && gate.treeTests() == com.walpro.autobuilder.core.build.TestRunGate.TreeTests.NOT_COMPILING
                && ctx.outputPath() != null) {
            try {
                if (skeleton.apply(java.nio.file.Path.of(ctx.outputPath()), ctx.cycleName())) {
                    gate.runOnTree(ctx.cycleName() + "-skeleton-tests", required);
                }
            } catch (RuntimeException e) {
                log.warn("T6-T: skeleton first could not run: {}", e.getMessage());
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
        String userPrompt = EvolutionProtocolContract.getUserPrompt(refreshed, result, recombined,
                promptTokenBudget.promptHeadroomTokens(), isProtected);
        // T4-CQ: the paths the operator protected, and every write refused since the last prompt --
        // a refusal the model never hears about is a move it will make again.
        if (protectedPaths != null) {
            String block = protectedPaths.promptBlock();
            if (block != null) userPrompt = userPrompt + "\n\n" + block;
        }
        // T6-T: a skeleton work item names its bodies and what the types around them offer
        var goals = goalPlannerService == null ? null : goalPlannerService.getIfAvailable();
        if (goals != null && refreshed.currentSubGoalId() != null && refreshed.outputPath() != null
                && com.walpro.autobuilder.core.build.BlockerTypes.GATE_SKELETON.equals(goals.getGoalMetadataString(
                        refreshed.currentSubGoalId(), com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY))) {
            String type = goals.getGoalMetadataString(refreshed.currentSubGoalId(), com.walpro.autobuilder.core.build.BlockerTypes.SKELETON_CLASS_KEY);
            String tests = goals.getGoalMetadataString(refreshed.currentSubGoalId(), com.walpro.autobuilder.core.build.BlockerTypes.SKELETON_TESTS_KEY);
            String block = com.walpro.autobuilder.protocol.evolution.SkeletonItemPromptBlock.render(
                    java.nio.file.Path.of(refreshed.outputPath()), type, tests == null ? List.of() : List.of(tests.split(",")),
                    Math.max(0, promptTokenBudget.promptHeadroomTokens()
                            - com.walpro.autobuilder.protocol.PromptTokenBudget.estimateTokens(userPrompt)) / 4);
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

        LLMResponseResult<EvolutionOutcome> response = llmAgentProtocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                raw -> EvolutionProtocolContract.isValidResponse(raw)
                        && EditHunks.apply(com.walpro.autobuilder.protocol.evolution.EvolutionResponseParser.normalizeFileHeaders(raw),
                                currentTree(refreshed)).problems().isEmpty()
                        && EditHunks.partialRewrites(com.walpro.autobuilder.protocol.evolution.EvolutionResponseParser.normalizeFileHeaders(raw),
                                currentTree(refreshed)).isEmpty(),
                raw -> parser.parse(raw, currentTree(refreshed)),
                raw -> {
                    // T4-EB: an edit whose SEARCH text does not occur exactly once is asked again, with where.
                    String format = EvolutionProtocolContract.describeMismatch(raw);
                    if (format != null) return format;
                    String normalized = com.walpro.autobuilder.protocol.evolution.EvolutionResponseParser.normalizeFileHeaders(raw);
                    List<String> problems = new java.util.ArrayList<>(EditHunks.apply(normalized, currentTree(refreshed)).problems());
                    problems.addAll(EditHunks.partialRewrites(normalized, currentTree(refreshed)));
                    return problems.isEmpty() ? null : "an edit could not be applied: " + String.join("; ", problems);
                }
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
