package com.walpro.autobuilder.protocol.planner;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.RequirementPhrasingNormalizer;
import com.walpro.autobuilder.protocol.abandoned.RelatedAbandonedWorkBlock;
import com.walpro.autobuilder.protocol.build.BuildResultPromptBlock;
import com.walpro.autobuilder.protocol.build.RolledBackPromptBlock;
import com.walpro.autobuilder.protocol.core.ContractRule;
import com.walpro.autobuilder.protocol.core.ProblemContextPromptBlocks;
import com.walpro.autobuilder.protocol.evolution.RejectionFeedbackPromptBlock;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PlannerProtocolContract {

    public static final String VERSION = "1.0-planner";

    /**
     * T2-EI: the first decomposition is depth-first, one resource at a time ({@code DEPTH_FIRST_RESOURCE}).
     * The breadth-first {@code FULL_COVERAGE_REQUIRED} variant was removed on 2026-10-04 with its switch:
     * every measured run used depth-first. See [[project-t2ei-rule-interaction-map]].
     */
    public static List<ContractRule> rules() {
        return List.of(
                new ContractRule("SUBGOAL_APPEND",
                        "To append subgoals, use #SubGoalAppend followed by a list of goals.",
                        "#SubGoalAppend\n- Improve logging\n- Refactor tests"
                ),
                new ContractRule("SUBGOAL_REMOVE",
                        "To remove existing subgoals, use #SubGoalRemove followed by the goal IDs (not descriptions) taken from the Remaining Goals list above.",
                        "#SubGoalRemove\n- goal-550e8400-e29b-41d4-a716-446655440000"
                ),
                new ContractRule("INCLUDE_ACTIONABLE_RESPONSE",
                        "Always include at least one actionable change (#SubGoalAppend, Update, or Remove).",
                        "#SubGoalAppend\n- Improve performance"
                ),
                new ContractRule("NO_EXTRA_COMMENTARY",
                        "Output ONLY the structured response (#SubGoalAppend / #SubGoalRemove, optionally followed by 'Message:'). Do not output any text before, after, or between these blocks — no explanations, no preamble, no markdown prose.",
                        "Message: Adjusted subgoals to better align with performance improvements."
                ),
                new ContractRule("NO_JSON",
                        "Do NOT output JSON. Use only the plain-text format defined in these rules (#SubGoalAppend, #SubGoalRemove, Message:).",
                        "#SubGoalAppend\n- Improve logging\nMessage: Added logging subgoal."
                ),
                new ContractRule(
                        "RESTRICT_TO_SOFTWARE_SCOPE",
                        "Only suggest subgoals related to software development (e.g., refactoring, testing, API design). Business tasks like 'conduct market research' or 'identify stakeholders' are not allowed.",
                        "#SubGoalAppend\n- Improve logging\n- Refactor controller"
                ),
                new ContractRule(
                        "NO_DUPLICATES",
                        "Do NOT re-emit goals that are semantically the same as existing remaining or completed goals.",
                        "Check phrasing and intent against the current goal lists."
                ),
                new ContractRule(
                        "PROBLEM_AWARENESS",
                        "If a PROBLEM CONTEXT block is present, you must consider it. If there are OPEN problems in scope, your subgoal changes must address them (directly or by adding diagnostic subgoals). Do not ignore problem keywords.",
                        """
                        #SubGoalAppend
                        - Investigate and fix keywords: STAGNATION, path:src/main/java/com/example/demo/HelloController.java
                        - Add a targeted test reproducing the failing behavior referenced by the problem context
                        Message: Added subgoals to address open in-scope problems before further feature work.
                        """
                ),
                new ContractRule(
                        "BUILD_FIRST",
                        "When the BUILD STATE block shows COMPILE_ERROR, do NOT add unrelated feature subgoals. " +
                        "If no subgoal tagged [BUILD_FIX] is active, append exactly one BUILD_FIX subgoal naming the " +
                        "diagnostic. Do NOT remove or restructure an active [BUILD_FIX] subgoal until COMPILE_OK is observed.",
                        """
                        BUILD STATE: COMPILE_ERROR — Compiler errors: ... cannot find symbol: HelloController
                        #SubGoalAppend
                        - [BUILD_FIX] Resolve missing symbol HelloController referenced by HelloApplication
                        Criteria:
                        * Project compiles without errors
                        * Compile error reported in the previous build is resolved
                        Message: Build is broken — appending a build-fix subgoal before any feature work.
                        """
                ),
                new ContractRule(
                        "BUILD_FIX_DIVERGENCE_REQUIRED",
                        "When a `--- BUILD FIX LOCUS STUCK ---` block is present, the build has failed N≥2 consecutive times " +
                        "on the same file with the same compile error. Do NOT propose another subgoal that edits this file. " +
                        "REUSE BEFORE CREATE: first check the project structure/symbol context for an EXISTING type with the " +
                        "same entity and role under a slightly different name (a minted synonym — e.g. a paraphrased exception " +
                        "or request type). If one exists, do NOT create a new file; instead correct the reference in the named " +
                        "file to use that existing type's exact name. ONLY when no such type exists, either propose creating the " +
                        "missing artifact named in the error (a new file at the expected path) or propose reverting/removing the " +
                        "change in the named file that introduced the bad reference.",
                        """
                        BUILD FIX LOCUS STUCK: 3 consecutive rollbacks on src/.../GlobalExceptionHandler.java with CANNOT_FIND_SYMBOL: BookAlreadyLoanedException
                        #SubGoalAppend
                        - [BUILD_FIX] Create BookAlreadyLoanedException.java at src/main/java/com/example/library/exception/ — extends RuntimeException, takes a String message.
                        Criteria:
                        * BookAlreadyLoanedException.java exists and defines the class
                        * GlobalExceptionHandler.java compiles without errors
                        Message: Diverged — creating the missing class instead of re-editing the file that references it.
                        """
                ),
                new ContractRule(
                        "BLOCKERS_FIRST",
                        "If BlockingInScope > 0, prioritize blocker-resolution subgoals first. Do not propose unrelated improvements while blockers are open.",
                        """
                        #SubGoalRemove
                        - goal-550e8400-e29b-41d4-a716-446655440000
                        #SubGoalAppend
                        - Fix blocker keywords: NO_MEANINGFUL_CHANGE, path:src/main/java/com/example/demo/HelloController.java
                        - Add regression test to ensure the fix remains stable
                        Message: Reordered subgoals to resolve blockers first.
                        """
                ),
                new ContractRule(
                        "KEYWORDS_TO_SUBGOALS",
                        "Translate problem keywords into concrete, software-scoped subgoals. Use file paths from keywords (path:...) when present to make subgoals specific and actionable.",
                        """
                        #SubGoalAppend
                        - Refactor src/main/java/com/example/demo/HelloController.java to remove duplication flagged by keywords: REDUNDANT_OUTPUT
                        - Add unit tests for the refactored behavior
                        Message: Converted problem keywords into file-targeted subgoals.
                        """
                ),
                new ContractRule(
                        "NO_REPEAT_KNOWN_FAILURES",
                        "If the problem keywords indicate repetition or stagnation (e.g., REPEAT, REPETITION_DETECTED, STAGNATION, NO_MEANINGFUL_CHANGE), do not propose semantically identical subgoals again. Propose a different diagnostic or structural angle.",
                        """
                        #SubGoalAppend
                        - Compare last accepted vs current diff for the files referenced by keywords and identify missing structural changes
                        - Introduce a new abstraction or test harness to force a meaningful change
                        Message: Avoided repeating prior subgoals; added a different diagnostic approach due to repetition/stagnation.
                        """
                ),
                new ContractRule(
                        "NO_REPEAT_ABANDONED",
                        "Do NOT re-propose abandoned goals verbatim. If an Abandoned Goals block is present, every goal listed there was already attempted and given up. If a similar approach is still warranted, reframe it with a concrete structural difference (different file, different technique, or narrower scope).",
                        "#SubGoalAppend\n- Refactor HelloController endpoint routing using a dedicated RequestMapping strategy (prior attempt used inline mapping — try a separate config class instead)"
                ),
                new ContractRule(
                        "SUBGOAL_REACTIVATE",
                        "When the PROPOSAL FEEDBACK block lists previously GIVEN_UP goals that still represent load-bearing work, you may explicitly reactivate them with #SubGoalReactivate followed by goal IDs (one per line). This is an alternative to re-proposing the same description — pick reactivation when the prior attempt left useful merged files on disk and only stalled out, not when the approach itself was wrong.",
                        "#SubGoalReactivate\n- goal-550e8400-e29b-41d4-a716-446655440000\nMessage: Reactivated prior attempt — merged files remain useful, just need follow-through."
                ),
                new ContractRule(
                        "GOAL_DERIVED_SUBGOALS",
                        "Every proposed subgoal must be directly traceable to a specific requirement stated in the root goal. " +
                        "Do not add generic boilerplate subgoals (e.g., 'Return Hello World', 'Add logging', 'Set up health check') " +
                        "unless the root goal explicitly requires them. " +
                        "If the root goal specifies an API contract (endpoints, HTTP codes, request/response format, validation rules), " +
                        "subgoals must implement those specifics — not intermediate scaffolding that the goal does not mention.",
                        """
                        Root Goal: REST API with POST /tasks, GET /tasks, PATCH /tasks/{id}/complete.
                        WRONG:
                        #SubGoalAppend
                        - Set up a controller returning "Hello World" at /
                        - Add a health check endpoint
                        OK:
                        #SubGoalAppend
                        - Implement POST /tasks with title validation (max 100 chars), returning HTTP 201 with task body or HTTP 400 with {\"message\":\"...\"} on invalid input
                        - Implement GET /tasks returning in-memory task list
                        - Implement PATCH /tasks/{id}/complete returning HTTP 404 for unknown id
                        """
                ),
                new ContractRule(
                        "ACCEPTANCE_CRITERIA",
                        "Every subgoal line in #SubGoalAppend MUST be followed immediately by a 'Criteria:' sub-block. " +
                        "List 1-4 short 'done when' statements prefixed with '*'. " +
                        "Each criterion must be specific and mechanically checkable (e.g., 'POST /tasks returns HTTP 201 with task body'). " +
                        "Criteria must describe OBSERVABLE BEHAVIOUR (endpoints, status codes, response bodies, stored state) — " +
                        "NEVER internal structure such as method names, field names, or helper classes. The implementation may " +
                        "choose any internal shape that produces the behaviour; a criterion like 'validateMemberLoanLimit method exists' " +
                        "can stay unmet forever while the behaviour is correct. " +
                        "If no observable output criterion applies (e.g., the subgoal is purely structural), write exactly: " +
                        "'* " + RequirementPhrasingNormalizer.STRUCTURAL_CRITERION_PLACEHOLDER + "'.",
                        """
                        #SubGoalAppend
                        - Implement POST /tasks with title validation
                        Criteria:
                        * Returns HTTP 201 with task body on success
                        * Returns HTTP 400 with {"message":"..."} body when title is missing or > 100 chars
                        * Title field is stored and returned in response
                        - Refactor controller to use a dedicated RequestMapping config class
                        Criteria:
                        * %s
                        - Implement GET /tasks
                        Criteria:
                        * GET /tasks returns HTTP 200 with a JSON array of tasks
                        * Response includes all previously created tasks
                        """.formatted(RequirementPhrasingNormalizer.STRUCTURAL_CRITERION_PLACEHOLDER)
                ),
                new ContractRule(
                        "CROSS_CUTTING_INVARIANTS",
                        "Before writing acceptance criteria for each subgoal, re-read the Root Goal for constraints " +
                        "that apply universally across ALL subgoals — not just the one you are currently planning. " +
                        "These may include: error handling conventions, output format requirements, data persistence strategy, " +
                        "naming or coding standards, security requirements, or any constraint explicitly stated as global. " +
                        "For each such universal constraint, add at least one criterion to every affected subgoal's Criteria block. " +
                        "Omitting a universal constraint from criteria is a planning error — evaluators only enforce what appears as a criterion. " +
                        "A constraint that files must NOT change is checked against the files changed since the project was adopted, " +
                        "not read from the code, when its criterion is written in exactly one of these forms (fill in the path or class): " +
                        String.join("; ", com.walpro.autobuilder.core.build.UnchangedClaims.TEMPLATES.stream()
                                .map(f -> "'" + f + "'").toList()) + ".",
                        """
                        Root Goal: Build a task manager. All errors must be reported with a human-readable reason. \
Data must never be written to disk.

                        OK:
                        #SubGoalAppend
                        - Implement task creation
                        Criteria:
                        * Task is added to the in-memory store and returned on success
                        * Invalid input produces an error with a human-readable reason
                        * No file I/O or disk persistence is used

                        WRONG (universal constraints missing from criteria):
                        #SubGoalAppend
                        - Implement task creation
                        Criteria:
                        * Task is added and returned on success
                        """
                ),
                new ContractRule(
                        "SHARED_STATE_RULE",
                        "If a subgoal implements a component that reads or writes data managed by another component " +
                        "(from a sibling subgoal), its criteria MUST include an explicit requirement that it accesses " +
                        "the shared data source — not an isolated private copy. " +
                        "A component that holds its own private copy of data owned elsewhere will produce incorrect " +
                        "results at runtime because it will never see writes made by the owning component.",
                        """
                        Root Goal: Order processor. Orders are created by one module; the billing module must charge for them.

                        OK:
                        #SubGoalAppend
                        - Implement billing
                        Criteria:
                        * Billing reads orders from the shared order store, not a private copy
                        * Charges reflect orders created by the order-creation module

                        WRONG (missing shared-state criterion — billing will always see an empty order list):
                        #SubGoalAppend
                        - Implement billing
                        Criteria:
                        * Billing correctly computes the charge for an order
                        """
                ),
                new ContractRule(
                        "PRUNE_BEFORE_GROW",
                        "When the remaining goal count is large relative to completed goals, review the backlog before adding more. Specifically look for: (1) remaining goals with overlapping or near-identical intent, (2) goals whose only step is to run, start or inspect something rather than change a file — the system itself compiles, tests and starts the project (see the capability manifest), (3) goals superseded by a more recent or specific sibling. Remove goals matching these criteria, then add new ones if still needed.",
                        "#SubGoalRemove\n- goal-550e8400-e29b-41d4-a716-446655440000\n- goal-6ba7b810-9dad-11d1-80b4-00c04fd430c8\nMessage: Removed two near-duplicate analysis goals before adding a more targeted one.\n#SubGoalAppend\n- Refactor HelloController to return structured JSON response"
                ),
                new ContractRule(
                        "DEPTH_FIRST_RESOURCE",
                        "Build ONE resource completely before starting the next. A 'resource' is a single domain entity together with all of its layers (model, repository, service, controller, endpoints, and its own exceptions). For your FIRST planning response, pick the SINGLE most foundational resource named in the root goal and emit subgoals ONLY for that one resource, in dependency order (model → repository → service → controller/endpoints → exceptions). Do NOT emit subgoals for any other resource yet — once this resource is complete and the backlog is empty you will be asked to decompose the next uncovered resource. Keep the active backlog small: one resource at a time.",
                        """
                        Root Goal: REST API for Books and Members.
                        #SubGoalAppend
                        - Implement the Book domain model with id, title, isbn fields
                        Criteria:
                        * Book persists with required fields
                        - Implement the Book repository
                        Criteria:
                        * (subgoal is structural — no observable output criterion)
                        - Implement POST /books returning 201 with the created book
                        Criteria:
                        * POST /books returns HTTP 201 with the book body
                        Message: Building the Book resource fully before starting Members.
                        """
                    ),
                new ContractRule(
                        "INDEPENDENT_ENDPOINT_SPLIT",
                        "When a single subgoal's Criteria block references two or more independent HTTP endpoints or features (e.g. criteria mention both 'POST /loans' and 'GET /members'), split it into one subgoal per endpoint, each with its own Criteria block. Do NOT split subgoals whose criteria all address the same endpoint or feature — coordinated multi-file work for one endpoint stays as one subgoal.",
                        "#SubGoalAppend\n- Implement POST /loans endpoint\nCriteria:\n* POST /loans creates a loan record and returns 201\n* POST /loans returns 404 when book not found\n- Implement GET /members endpoint\nCriteria:\n* GET /members returns paginated list of members\n* GET /members returns 200 with JSON array"
                ),
                new ContractRule(
                        "PLAN_RATIONALE",
                        "When proposing 2 or more subgoals in a single #SubGoalAppend, include a 'PlanRationale:' line BEFORE the #SubGoalAppend block. " +
                        "Write 1-2 sentences describing what the batch collectively achieves and how the subgoals relate to each other. " +
                        "This rationale will be shown to the agents executing each subgoal so they understand their scope within the plan. " +
                        "Omit PlanRationale: when only one subgoal is proposed.",
                        """
                        PlanRationale: These 3 subgoals each address an independent layer of the feature; they are ordered so that each one builds on a stable foundation left by the previous. Agents should evaluate each subgoal only on its own layer — absent functionality from sibling subgoals is intentional and expected.
                        #SubGoalAppend
                        - Add the data model for the new domain object
                        Criteria:
                        * Domain object persists correctly with required fields
                        - Implement the business logic that operates on it
                        Criteria:
                        * Core operation produces the correct output for valid input
                        - Expose the behaviour through the public interface
                        Criteria:
                        * Public interface accepts input and returns the expected result
                        """
                )
        );
    }

    public static String asPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("PLANNER PROTOCOL CONTRACT v").append(VERSION).append("\n\n");
        for (ContractRule rule : rules()) {
            sb.append("RULE: ").append(rule.id()).append("\n")
                    .append("Description: ").append(rule.description()).append("\n")
                    .append("Example: ").append(rule.example()).append("\n\n");
        }
        return sb.toString();
    }

    public static String getSystemPrompt() {
        return "You are a goal planning agent.\n\n" + asPromptBlock();
    }

    public static String getUserPrompt(CycleContextData input) {
        StringBuilder sb = new StringBuilder();

        // T4-CD slice 2: a resumed run's first planning consults the parent run's end report —
        // how the lineage's previous run ended and what blocked it — before re-deriving a plan.
        if (input.priorRunEndReport() != null) {
            sb.append(input.priorRunEndReport()).append("\n\n");
        }

        InterpretationResult interp = input.evaluationInterpretation();

        String interpretationBlock = interp == null
            ? "(no interpretation available)" : """
                   Current Score: %.3f
                   Last Score: %.3f
                   Delta: %.4f
                   Goal progress: %s
                """.formatted(interp.current(), interp.previous(), interp.delta(), interp.goalProgress());

        String skippedBlock = (input.skippedGoals() == null || input.skippedGoals().isEmpty())
                ? null
                : "The following proposals were rejected last cycle as too similar to existing goals.\n"
                  + "Do NOT re-propose them verbatim or with only cosmetic rewording:\n"
                  + input.skippedGoals().stream().map(g -> "- " + g).collect(Collectors.joining("\n"));

        // T1-EI: PROPOSAL FEEDBACK block listing dedup-blocked proposals + the matched
        // GIVEN_UP goal ids the planner can choose to reactivate explicitly. Renders only when
        // both a skip set AND a matched-id map are present (avoids noise on clean cycles).
        String proposalFeedbackBlock = proposalFeedbackBlock(input);

        String problemBlock = ProblemContextPromptBlocks.asPromptBlock(
                input.problemContext(),
                input.currentGoalId(),
                input.currentSubGoalId()
        );

        String subgoalStatusBlock = currentSubGoalStatusBlock(input);
        String projectStructBlock = projectStructureBlock(input);
        // T2-CU: render BUILD STATE so the planner sees compile-error context before deciding
        // which subgoals to add or remove.
        String buildStateBlock = BuildResultPromptBlock.render(input, BuildResultPromptBlock.Audience.PLANNER);
        // T1-DX: also render previous-cycle rollback observation so the planner doesn't repeat
        // the subgoal that just rolled back.
        String rolledBackBlock = RolledBackPromptBlock.render(input);
        // T4-BX: relay the authored rejection reasons so the planner redirects the plan. The
        // evaluator's feedbackMessage is already shown in the EVALUATION FEEDBACK block above, so
        // exclude it here — this block's added value for the planner is the heuristic rationales.
        String rejectionBlock = RejectionFeedbackPromptBlock.render(input, input.evaluationFeedback());
        // T4-BX: relay the analysis agent's authored observations so the planner plans around what
        // analysis found blocking (e.g. a missing required class) instead of re-queuing dead ends.
        String analysisObservationsBlock = com.walpro.autobuilder.protocol.analysis
                .AnalysisObservationsPromptBlock.render(input);
        // T2-DD: render BUILD FIX LOCUS STUCK when ≥2 consecutive rollbacks share the same
        // (errorKind, path). Triggers the planner's BUILD_FIX_DIVERGENCE_REQUIRED rule.
        String locusStuckBlock = renderLocusStuckBlock(input);
        // Surface deterministic compile fixes the injector applied this cycle so the planner
        // does not re-queue [BUILD_FIX] subgoals for problems that have already been resolved
        // by the resolver chain (and so it knows which dependency landed automatically).
        String injectorFixesBlock = renderInjectorFixesBlock(input);
        // T2-DL: surface related GIVEN_UP clusters + orphaned merged files so the planner
        // extends prior work or reactivates abandoned subgoals rather than re-proposing
        // verbatim. Pairs with T1-EI's rehabilitation path.
        String relatedAbandonedBlock = RelatedAbandonedWorkBlock.render(
                input, RelatedAbandonedWorkBlock.Audience.PLANNER);
        // T4-DA: the specification clauses no work item articulates. The planner is the agent
        // that can act on this directly — an unarticulated clause is the strongest candidate for
        // the next subgoal — and until now nothing computed the question at all outside the
        // closure edge, which a run ending on the deadline never reaches.
        String seedCoverageBlock = com.walpro.autobuilder.protocol.coverage.SeedCoverageBlock.render(
                input.seedCoverage(), com.walpro.autobuilder.protocol.coverage.SeedCoverageBlock.Audience.PLANNER);

        int remainingCount = input.remainingGoals() == null ? 0 : input.remainingGoals().size();
        int completedCount = input.completedGoals() == null ? 0 : input.completedGoals().size();
        // T4-DA consumer 1: the plan-versus-seed readout, from the SAME SeedCoverageAnalyzer the
        // analysis prompt reads (2026-07-30). This block used to render a second comparator built
        // on SeedRequirements — one invariant, two extraction methods, able to disagree inside one
        // run's prompts. SeedRequirements now serves only the CLOSURE edge, where the question is
        // artifact-versus-seed rather than plan-versus-seed.
        String seedCoverage = com.walpro.autobuilder.protocol.coverage.SeedCoverageBlock.render(
                input.seedCoverage(),
                com.walpro.autobuilder.protocol.coverage.SeedCoverageBlock.Audience.PLANNER);
        if (seedCoverage != null && !seedCoverage.isBlank()) {
            sb.append(seedCoverage).append("\n\n");
        }
        sb.append("""
            --- GOALS ---
            Root Goal: %s
            Current SubGoal: %s

            Remaining Goals (%d) — indented entries are active subgoals of the item above them; do NOT remove a parent while it still has active subgoals:
            %s

            Completed Goals (%d):
            %s

            Abandoned Goals (given up — do not re-propose verbatim):
            %s
            %s
            %s
            %s
            --- EVALUATION FEEDBACK ---
            %s
            \s
            %s
            %s
            --- LAST SCORE INTERPRETATION ---
            %s

            --- RECOMMENDATION ---
            %s

            %s

            --- INTERPRETATION CONTEXT (previous cycle) ---
            %s

            %s
            %s
            %s
            %s
            %s
            %s
            \s
            %s
            --- RESPONSE FORMAT ---
            Respond with one or more of:
            - #SubGoalAppend followed by subgoal list (to add new subgoals)
            - #SubGoalRemove followed by goal IDs (from the Remaining Goals list above) (to remove existing subgoals)
            To replace the subgoal list: use #SubGoalRemove to remove unwanted goals, then #SubGoalAppend to add new ones.
            When proposing 2 or more subgoals: include PlanRationale: <1-2 sentences> BEFORE the #SubGoalAppend block (see PLAN_RATIONALE rule).
            Optionally include: Message: <short summary>
            Output ONLY the structured blocks above. Do not add any text before, after, or between them.
            IMPORTANT: Every entry in #SubGoalAppend MUST be followed immediately by a Criteria: block with at least one testable criterion (see ACCEPTANCE_CRITERIA rule). Subgoals without a Criteria: block will be silently discarded.
           \s""".formatted(
                input.currentGoalDescription() == null ? "(none)" : input.currentGoalDescription(),
                input.currentSubGoalDescription() == null ? "(none)" : input.currentSubGoalDescription(),
                remainingCount,
                formatGoalInfoList(input.remainingGoals()),
                completedCount,
                formatGoalInfoList(input.completedGoals()),
                formatDescriptionList(input.abandonedGoals()),
                skippedBlock == null ? "" : "\n--- REJECTED PROPOSALS (too similar — do not re-propose) ---\n" + skippedBlock + "\n",
                proposalFeedbackBlock == null ? "" : proposalFeedbackBlock + "\n",
                subgoalStatusBlock == null ? "" : subgoalStatusBlock + "\n",
                input.evaluationFeedback() == null ? "(none)" : input.evaluationFeedback(),
                rejectionBlock == null ? "" : rejectionBlock + "\n",
                analysisObservationsBlock == null ? "" : analysisObservationsBlock + "\n",
                interpretationBlock,
                input.recommendation() == null ? "(none)" : input.recommendation(),
                problemBlock,
                interpretationContext(input),
                buildStateBlock == null ? "" : buildStateBlock + "\n",
                rolledBackBlock == null ? "" : rolledBackBlock + "\n",
                locusStuckBlock == null ? "" : locusStuckBlock + "\n",
                injectorFixesBlock == null ? "" : injectorFixesBlock + "\n",
                relatedAbandonedBlock == null ? "" : relatedAbandonedBlock + "\n",
                seedCoverageBlock == null ? "" : seedCoverageBlock + "\n",
                projectStructBlock == null ? "" : projectStructBlock + "\n"
        ));
        if (input.currentSubGoalDescription() == null) {
            // T2-EI: depth-first empty-backlog edge — decompose the NEXT single uncovered
            // resource rather than re-asserting full breadth coverage. The completion coverage
            // gate (re-armed for depth-first) is the closure-side half of the same invariant.
            sb.append("""
                \n\nNOTE:
                There is currently no active subgoal.
                Before responding, list every named resource (domain entity) in the root goal spec.
                Compare against the Completed Goals list above.
                - If EVERY resource is fully implemented (each has its completed model/repository/service/controller subgoals) → respond with: Message: Goal complete.
                - If ANY resource is not yet started or only partially done → use #SubGoalAppend to decompose the NEXT single uncovered resource, in dependency order. Decompose ONE resource only.
                Do NOT say "Goal complete" while any resource from the root goal spec lacks completed subgoals.
                Do NOT decompose more than one resource at a time.
                """);
        }
        
//        String lineage = input.currentSubGoalId(); // fallback if no explicit lineage
//        String strategyHint = StrategyHintGenerator.generateHint(lineage, input.depthOrZero());
//        sb.append("\n--- BRANCHING STRATEGY ---\n");
//        sb.append("Suggested Drift: ").append(strategyHint).append("\n");

        return sb.toString();
    }

    /**
     * T1-EI: PROPOSAL FEEDBACK block — renders when the planner's previous wave was dedup-blocked
     * against GIVEN_UP goals. Lists the blocked proposals with the matched goal id and offers
     * the LLM three explicit recovery actions (reactivate / remove + replace / abstract up).
     * Only renders when the planner emitted {@code skippedProposalMatches} on the last run —
     * the existing REJECTED PROPOSALS block continues to cover the generic dedup case.
     */
    static String proposalFeedbackBlock(CycleContextData ctx) {
        if (ctx == null) return null;
        Map<String, String> matches = ctx.skippedProposalMatches();
        if (matches == null || matches.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("--- PROPOSAL FEEDBACK ---\n");
        sb.append("The following proposals were dedup-blocked against GIVEN_UP goals last cycle. ")
          .append("Re-proposing them verbatim will be blocked again — pick one of the explicit ")
          .append("recovery actions below instead of restating the same description.\n");
        for (Map.Entry<String, String> e : matches.entrySet()) {
            sb.append("- proposal: ").append(e.getKey()).append("\n")
              .append("    blocked against: ").append(e.getValue()).append("\n");
        }
        sb.append("\nRecovery actions:\n")
          .append("  (a) #SubGoalReactivate <id>  — pick when prior work left useful merged files on disk and only stalled out.\n")
          .append("  (b) #SubGoalRemove <id> followed by #SubGoalAppend with a structurally different replacement\n")
          .append("      (different file, different technique, narrower scope).\n")
          .append("  (c) #SubGoalAppend at a different abstraction level (e.g. higher-level coordination subgoal\n")
          .append("      that the original GIVEN_UP attempt was a leaf of).\n");
        return sb.toString();
    }

    private static String currentSubGoalStatusBlock(CycleContextData ctx) {
        if (ctx.currentSubGoalDescription() == null) return null;

        StringBuilder sb = new StringBuilder("--- CURRENT SUBGOAL STATUS ---\n");

        int stagnating = ctx.cumulativeStagnatingCycles();
        sb.append("Stagnating cycles: ").append(stagnating).append("\n");

        if (ctx.lastScore() != null) {
            double delta = ctx.score() - ctx.lastScore();
            sb.append("Score: %.3f → %.3f (Δ %+.4f)\n".formatted(ctx.lastScore(), ctx.score(), delta));
        } else if (ctx.score() > 0) {
            sb.append("Score: %.3f\n".formatted(ctx.score()));
        }

        List<String> criteria = ctx.currentSubGoalCriteria();
        List<String> assessments = ctx.criteriaAssessments();
        if (criteria != null && !criteria.isEmpty()) {
            sb.append("Acceptance criteria:\n");
            for (int i = 0; i < criteria.size(); i++) {
                String mark = findAssessmentMark(assessments, i + 1);
                sb.append("  [").append(i + 1).append("] ").append(mark)
                  .append(" ").append(criteria.get(i)).append("\n");
            }
        }

        List<String> warnings = ctx.persistentHeuristicWarnings();
        if (warnings != null && !warnings.isEmpty()) {
            sb.append("Persistent heuristic warnings:\n");
            for (String w : warnings) {
                sb.append("  \u26a0 ").append(w).append("\n");
            }
        }

        // T2-BG: show the decompose coaching note on formal stagnation (>=3 cycles) OR when
        // REPLAN_REQUESTED is strong (>=0.50), which means the subgoal is stuck regardless of
        // how many formal STAGNATING cycles have been recorded. In REPLAN mode (pre-T1-BD),
        // evolution was blocked so STAGNATING results were impossible and the counter never
        // reached 3; with REPLAN_REQUESTED as an additional trigger the coaching fires sooner.
        boolean replanRequested = ctx.hasStrongRequest(BasicSignals.REPLAN_REQUESTED, 0.50);
        if (stagnating >= 3 || replanRequested) {
            String trigger = stagnating >= 3
                    ? stagnating + " stagnating cycles"
                    : "REPLAN_REQUESTED signal (subgoal is stuck)";
            sb.append("\n\u26a0 This subgoal is stuck (").append(trigger).append(").")
              .append(" Consider decomposing: use `#SubGoalRemove <id>` to remove it, then")
              .append(" `#SubGoalAppend` with narrower, file-level replacements.")
              .append(" Removal is processed before injection — the backlog will be empty in time for the new subgoals.\n");
        }

        return sb.toString().stripTrailing();
    }

    private static String findAssessmentMark(List<String> assessments, int index) {
        if (assessments == null || assessments.isEmpty()) return "?";
        String prefix = "[" + index + "]";
        for (String a : assessments) {
            if (a.startsWith(prefix)) {
                if (a.contains("\u2713")) return "\u2713";
                if (a.contains("\u2717")) return "\u2717";
            }
        }
        return "?";
    }

    /**
     * T2-DD: renders the BUILD FIX LOCUS STUCK block when consecutive rollbacks on the same
     * (errorKind, path) have reached the streak threshold. Pairs with the
     * {@code BUILD_FIX_DIVERGENCE_REQUIRED} contract rule.
     */
    private static String renderLocusStuckBlock(CycleContextData ctx) {
        if (ctx == null) return null;
        if (ctx.sameFailureStreak() < 2) return null;
        String key = ctx.sameFailureStreakKey();
        if (key == null) return null;
        return "--- BUILD FIX LOCUS STUCK ---\n"
                + "Consecutive failed attempts with the same error: " + ctx.sameFailureStreak() + "\n"
                + "Streak key (errorKind|identity): " + key;
    }

    /**
     * Renders the deterministic compile fixes the injector applied this cycle. The planner
     * uses this to suppress redundant {@code [BUILD_FIX]} subgoals that name a missing
     * dependency the injector already resolved.
     */
    private static String renderInjectorFixesBlock(CycleContextData ctx) {
        if (ctx == null) return null;
        var fixes = ctx.injectorFixes();
        if (fixes == null || fixes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("--- INJECTOR FIXES THIS CYCLE ---\n");
        sb.append("Deterministic compile-fix injector applied the following edits automatically.\n");
        sb.append("Do NOT propose [BUILD_FIX] subgoals for these — they have already been resolved.\n");
        for (var fix : fixes) {
            sb.append("- ").append(fix.rule());
            if (fix.kind() != null) sb.append(" [").append(fix.kind().name()).append("]");
            if (fix.missingToken() != null && !fix.missingToken().isBlank()) {
                sb.append(" token=").append(fix.missingToken());
            }
            if (fix.touchedPaths() != null && !fix.touchedPaths().isEmpty()) {
                sb.append(" paths=").append(fix.touchedPaths());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String projectStructureBlock(CycleContextData ctx) {
        String summary = ctx.projectSummary();
        if (summary == null || summary.isBlank()) return null;

        String[] lines = summary.split("\n", -1);
        String content;
        if (lines.length <= 30) {
            content = summary;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                sb.append(lines[i]).append("\n");
            }
            sb.append("... (truncated)");
            content = sb.toString();
        }
        return "--- PROJECT STRUCTURE ---\n" + content;
    }

    private static String formatGoalInfoList(List<GoalEntity> goalEntities) {
        if (goalEntities == null || goalEntities.isEmpty()) return "(none)";

        Set<String> ids = goalEntities.stream().map(GoalEntity::getId).collect(Collectors.toSet());

        Map<String, List<GoalEntity>> childrenByParent = goalEntities.stream()
                .filter(g -> g.getParentId() != null && ids.contains(g.getParentId()))
                .collect(Collectors.groupingBy(GoalEntity::getParentId));

        List<GoalEntity> roots = goalEntities.stream()
                .filter(g -> g.getParentId() == null || !ids.contains(g.getParentId()))
                .toList();

        StringBuilder sb = new StringBuilder();
        for (GoalEntity root : roots) {
            appendGoalTree(sb, root, childrenByParent, 0);
        }
        return sb.toString().stripTrailing();
    }

    private static void appendGoalTree(StringBuilder sb, GoalEntity goal,
            Map<String, List<GoalEntity>> childrenByParent, int depth) {
        List<GoalEntity> children = childrenByParent.getOrDefault(goal.getId(), List.of());
        sb.append("    ".repeat(depth))
          .append("- ").append(goal.getId()).append(": ");
        // T2-CU: surface the [BUILD_FIX] tag inline so the planner can identify which subgoal
        // protects the build invariant and must not be restructured while the build is red.
        // Planner-created build-fix goals already carry the prefix in their description text
        // (creation tags metadata off that very prefix) — don't render it twice.
        String description = goal.getDescription();
        if (isBuildFix(goal) && (description == null
                || !description.trim().startsWith(com.walpro.autobuilder.core.build.BlockerTypes.BUILD_FIX_DESCRIPTION_PREFIX))) {
            sb.append("[BUILD_FIX] ");
        }
        sb.append(description);
        int merges = goal.getMergeCount();
        if (merges > 0) {
            sb.append(" [").append(merges).append(merges == 1 ? " merge" : " merges").append("]");
        }
        if (!children.isEmpty()) {
            sb.append(" [has ").append(children.size()).append(" active subgoal")
              .append(children.size() == 1 ? "" : "s").append(" — remove children before removing this]");
        }
        sb.append("\n");
        for (GoalEntity child : children) {
            appendGoalTree(sb, child, childrenByParent, depth + 1);
        }
    }

    private static boolean isBuildFix(GoalEntity goal) {
        if (goal == null || goal.getMetadata() == null) return false;
        return com.walpro.autobuilder.core.build.BlockerTypes.BUILD_FIX
                .equals(goal.getMetadata().get(com.walpro.autobuilder.core.build.BlockerTypes.METADATA_KEY));
    }

    @SuppressWarnings("unchecked")
    private static String formatDescriptionList(List<GoalEntity> goalEntities) {
        if (goalEntities == null || goalEntities.isEmpty()) return "(none)";
        return String.join("\n", goalEntities.stream()
                .map(g -> {
                    StringBuilder sb = new StringBuilder("- ").append(g.getDescription());
                    int merges = g.getMergeCount();
                    Object rawFiles = g.getMetadata() != null ? g.getMetadata().get("mergedFiles") : null;
                    List<String> files = rawFiles instanceof List<?> list
                            ? list.stream().map(Object::toString).toList()
                            : List.of();
                    Object reasonRaw = g.getMetadata() != null ? g.getMetadata().get("givenUpReason") : null;
                    String reason = reasonRaw instanceof String s ? s : null;
                    // T3-BP: fall back to entity resolution for paths that don't write givenUpReason
                    // metadata (cascade, planner replacement, per-parent exhaustion, fulfilled paths)
                    if (reason == null && g.getResolution() != null && !g.getResolution().isBlank()) {
                        reason = g.getResolution();
                    }
                    // Backtrace annotation
                    if (merges > 0 || !files.isEmpty() || reason != null) {
                        sb.append(" [");
                        if (merges > 0) sb.append(merges).append(merges == 1 ? " merge" : " merges");
                        if (!files.isEmpty()) {
                            if (merges > 0) sb.append(" | ");
                            sb.append("files: ").append(String.join(", ", files));
                        }
                        if (reason != null) {
                            if (merges > 0 || !files.isEmpty()) sb.append(" | ");
                            sb.append("gave up: ").append(reason);
                        }
                        sb.append("]");
                        if (!files.isEmpty()) {
                            sb.append("\n  → partial output already in output/ — prefer extending over rewriting");
                        }
                    }
                    return sb.toString();
                })
                .toList());
    }

    public static boolean isValidResponse(String content) {
        return content != null && (
                content.contains("#SubGoalUpdate") ||
                        content.contains("#SubGoalAppend") ||
                        content.contains("#SubGoalRemove")
        );
    }

    private static String interpretationContext(CycleContextData ctx) {
        if (ctx == null) return "(none)";
        String summary = ctx.interpretationSummary();
        String directives = ctx.interpretationDirectives();
        if ((summary == null || summary.isBlank()) && (directives == null || directives.isBlank())) return "(none)";
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) sb.append("Summary: ").append(summary.trim());
        if (directives != null && !directives.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("Directives: ").append(directives.trim());
        }
        return sb.toString();
    }

}
