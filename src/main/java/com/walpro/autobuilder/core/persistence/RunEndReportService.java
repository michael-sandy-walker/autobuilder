package com.walpro.autobuilder.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.RecurringDefectTracker;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * T4-CD slice 2: the run end report — a machine-readable terminal self-description written once
 * per pause episode at the same single home where T4-CA preserve and the {@code system_paused}
 * emit already hook. On resume, the next run of the same lineage reads it and consults the
 * parent's own diagnosis in its first prompts instead of re-deriving it over cycles
 * ([[project-introspection-as-north-star]] made concrete: the loop performs the session-start
 * re-grounding move on its own state).
 *
 * <p><b>Advisory, never constraining.</b> The end report records observations; the renderer
 * frames them as the prior run's beliefs to verify against the code on disk — loaded state is
 * hypothesis, not fact, and clamping the next run's exploration would flatten the good tail.
 *
 * <p><b>Ownership.</b> The end report carries the root goal id it was written under. The reader
 * refuses an end report whose root id differs from the loaded goal state — a foreign seed or a
 * re-seeded target must not inherit another lineage's diagnosis as "my prior work".
 *
 * <p>Lives in {@code meta/} — kept across a T4-CD resume, wiped by a full reset. Never throws.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunEndReportService {

    public static final String END_REPORT_FILE = "meta/run-end-report.json";
    // T3-CC slice 2: bumped when the criteria tier counts were added. A reader of a version-1
    // report must not assume the fields are absent because the run had no elaboration.
    static final String SCHEMA_VERSION = "2";

    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;
    private final ObjectMapper objectMapper;

    /** Dominant-defect tally — optional so pure-unit tests can run without the tracker. */
    @Setter
    @Autowired(required = false)
    private RecurringDefectTracker recurringDefectTracker;

    /**
     * T4-DK: the same exercisability question the probe path asks. Optional on the same grounds as
     * the tracker above; when it is absent the readout reports empty rather than reporting a
     * serving-only number as though it were the observed one.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.ExercisableCriterionQuery exercisableCriterionQuery;

    /** One open or abandoned work item with the criteria the parent run could not verify met. */
    public record OpenItem(String goalId, String status, String description,
                           List<String> unmetCriteria, String resolution) {}

    /** The parent run's dominant recurring defect, when its tally had one at the terminal. */
    public record DefectShape(String missingToken, double share, int subgoalSpan, boolean hadGreenFix) {}

    public record RunEndReport(
            String schemaVersion,
            String terminalReason,
            String haltCycle,
            Instant writtenAt,
            String rootGoalId,
            String rootGoalDescription,
            String rootStatus,
            int activeSubgoals,
            int completedSubgoals,
            int abandonedSubgoals,
            List<OpenItem> openWork,
            List<OpenItem> abandonedBlockers,
            DefectShape dominantDefect,
            List<String> lastRejectionReasons,
            boolean entryPointPresent,
            String preservedFromCycle,
            String preservedVerifiedBuild,
            /*
             * T3-CC: the two tiers of fulfilment, per run rather than per cycle.
             *
             * `criteriaDeclared` is every acceptance criterion in the tree; `criteriaElaboration`
             * is the subset the loop AUTHORED beyond the specification, which is reported and
             * never asserted against the artifact. Recorded so the elaboration RATE is watchable
             * across runs -- and deliberately not turned into a score, because rewarding it would
             * pay the loop to invent obligations it already invents often enough.
             */
            int criteriaDeclared,
            int criteriaElaboration) {}

    /**
     * Writes the end report for the pause episode that just reached its terminal. Call AFTER
     * T4-CA preserve at the halt home so the provenance on disk is this episode's own; the
     * fulfilled terminal has no preserve, and the provenance guard (haltCycle match) keeps a
     * parent run's provenance from being claimed as this run's.
     */
    public void write(CycleContextData context, String terminalReason) {
        try {
            GoalEntity root = goalPlannerService.getRootGoal();
            List<GoalEntity> remaining = goalPlannerService.getRemainingGoals();
            List<GoalEntity> completed = goalPlannerService.getCompletedGoals();
            List<GoalEntity> abandoned = goalPlannerService.getAbandonedGoals();

            List<OpenItem> openWork = new ArrayList<>();
            for (GoalEntity g : remaining) {
                openWork.add(toItem(g));
            }
            // Abandoned entries matter only when they carry a recorded unmet residue — those are
            // the true blockers; cascade-killed children without criteria are tree bookkeeping.
            List<OpenItem> blockers = new ArrayList<>();
            for (GoalEntity g : abandoned) {
                OpenItem item = toItem(g);
                if (!item.unmetCriteria().isEmpty()) {
                    blockers.add(item);
                }
            }

            DefectShape defect = null;
            if (recurringDefectTracker != null) {
                defect = recurringDefectTracker.dominantDefect()
                        .map(d -> new DefectShape(d.missingToken(), d.share(), d.subgoalSpan(), d.hadGreenFix()))
                        .orElse(null);
            }

            boolean entryPoint = EntryPointScan.hasJavaEntryPoint(fileService.resolveSecurePath("output"));

            String preservedFrom = null;
            String preservedVerified = null;
            PreservedArtifactService.Provenance provenance = readProvenance();
            String haltCycle = context != null ? context.cycleName() : null;
            if (provenance != null && haltCycle != null && haltCycle.equals(provenance.haltCycle())) {
                preservedFrom = provenance.fromCycle();
                preservedVerified = provenance.verifiedBuildResult();
            }

            // Reuses the readout rather than recounting: it is the one place that knows how a
            // criterion is classified, and a second count here would drift from the T2-EX line.
            var criteriaCounts = com.walpro.autobuilder.observability.CriteriaExecutabilityReadout
                    .over(goalPlannerService.getAllGoals(), exercisableCriterionQuery,
                            goalPlannerService.specification());
            RunEndReport endReport = new RunEndReport(
                    SCHEMA_VERSION,
                    terminalReason,
                    haltCycle,
                    Instant.now(),
                    root != null ? root.getId() : null,
                    root != null ? root.getDescription() : null,
                    root != null && root.getStatus() != null ? root.getStatus().name() : null,
                    remaining.size(),
                    completed.size(),
                    abandoned.size(),
                    openWork,
                    blockers,
                    defect,
                    context != null && context.rejectionFeedback() != null
                            ? List.copyOf(context.rejectionFeedback()) : List.of(),
                    entryPoint,
                    preservedFrom,
                    preservedVerified,
                    criteriaCounts.authored(),
                    criteriaCounts.elaboration());
            fileService.writeJson(END_REPORT_FILE, endReport);
            log.info("T4-CD: run end report written ({} — openWork={}, blockers={}, dominantDefect={}, "
                            + "criteria={} of which {} elaboration).",
                    terminalReason, openWork.size(), blockers.size(),
                    defect != null ? defect.missingToken() : "none",
                    criteriaCounts.authored(), criteriaCounts.elaboration());
        } catch (Exception e) {
            log.warn("T4-CD: run end report write failed (run state untouched): {}", e.getMessage());
        }
    }

    /**
     * Reads the parent run's end report for resume. Empty when absent, unreadable, or owned by a
     * DIFFERENT lineage ({@code rootGoalId} mismatch — the ownership check).
     */
    public java.util.Optional<RunEndReport> readForLineage(String expectedRootGoalId) {
        try {
            if (!fileService.exists(END_REPORT_FILE)) return java.util.Optional.empty();
            RunEndReport t = objectMapper.readValue(fileService.readString(END_REPORT_FILE), RunEndReport.class);
            if (expectedRootGoalId == null || t.rootGoalId() == null
                    || !expectedRootGoalId.equals(t.rootGoalId())) {
                log.info("T4-CD: end report on disk belongs to root {} but the loaded lineage is {} — not consulting it.",
                        t.rootGoalId(), expectedRootGoalId);
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(t);
        } catch (IOException | RuntimeException e) {
            log.warn("T4-CD: run end report unreadable (resuming without it): {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Renders the end report as the advisory prompt block the resumed run's first planner /
     * analysis / evolution prompts consult. Deliberately lean: the live goal tree is already
     * rendered elsewhere in those prompts — this block carries only what the tree cannot say:
     * how the parent run ended, what blocked it, and what it already tried.
     */
    public String renderPromptBlock(RunEndReport t) {
        if (t == null) return null;
        StringBuilder sb = new StringBuilder("--- PRIOR RUN OF THIS LINEAGE (advisory) ---\n");
        sb.append("This run continues work a previous run left off. That run ended: ")
          .append(t.terminalReason() == null ? "(unknown)" : t.terminalReason());
        sb.append(" (root ").append(t.rootStatus() == null ? "?" : t.rootStatus())
          .append(", ").append(t.completedSubgoals()).append(" completed / ")
          .append(t.activeSubgoals()).append(" open / ")
          .append(t.abandonedSubgoals()).append(" abandoned subgoals at its terminal).\n");
        if (t.preservedFromCycle() != null) {
            sb.append("The current output/ tree is its preserved artifact (")
              .append(t.preservedVerifiedBuild() == null ? "unverified" : t.preservedVerifiedBuild())
              .append(" at preserve time).\n");
        }
        if (t.dominantDefect() != null) {
            DefectShape d = t.dominantDefect();
            sb.append("Its dominant recurring defect: missing type ").append(d.missingToken())
              .append(String.format(java.util.Locale.ROOT, " (%.0f%% of its missing-type observations, across %d subgoals%s).%n",
                      d.share() * 100, d.subgoalSpan(),
                      d.hadGreenFix() ? "; a deterministic fix greened a probe for it once" : ""));
        }
        if (t.abandonedBlockers() != null && !t.abandonedBlockers().isEmpty()) {
            sb.append("Work it gave up with unmet criteria (verify against current code before re-attempting):\n");
            for (OpenItem item : t.abandonedBlockers()) {
                sb.append("  - ").append(item.description());
                if (!item.unmetCriteria().isEmpty()) {
                    sb.append(" [unmet: ").append(String.join("; ", item.unmetCriteria())).append("]");
                }
                sb.append("\n");
            }
        }
        if (t.lastRejectionReasons() != null && !t.lastRejectionReasons().isEmpty()) {
            sb.append("Why its final attempt was rejected:\n");
            for (String reason : t.lastRejectionReasons()) {
                sb.append("  - ").append(reason).append("\n");
            }
        }
        sb.append("These are the prior run's observations, not instructions — the code on disk is the ground truth.");
        return sb.toString();
    }

    private OpenItem toItem(GoalEntity g) {
        List<String> criteria = criteriaOf(g);
        Map<Integer, Boolean> passed = goalPlannerService.getCriteriaPassStatus(g);
        List<String> unmet = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            if (!Boolean.TRUE.equals(passed.get(i + 1))) {
                unmet.add(criteria.get(i));
            }
        }
        return new OpenItem(g.getId(),
                g.getStatus() != null ? g.getStatus().name() : null,
                g.getDescription(), unmet, g.getResolution());
    }

    @SuppressWarnings("unchecked")
    private static List<String> criteriaOf(GoalEntity g) {
        Object raw = g.getMetadata() != null ? g.getMetadata().get("acceptanceCriteria") : null;
        return raw instanceof List<?> l ? (List<String>) l : List.of();
    }

    private PreservedArtifactService.Provenance readProvenance() {
        try {
            if (!fileService.exists(PreservedArtifactService.PROVENANCE_FILE)) return null;
            return objectMapper.readValue(
                    fileService.readString(PreservedArtifactService.PROVENANCE_FILE),
                    PreservedArtifactService.Provenance.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
