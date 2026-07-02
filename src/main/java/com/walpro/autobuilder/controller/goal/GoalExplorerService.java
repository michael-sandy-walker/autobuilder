package com.walpro.autobuilder.controller.goal;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.AutobuilderService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.model.GoalProgressState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * T4-AK: composes {@link GoalPlannerService}, {@link OrchestrationSnapshotService} and
 * {@link GoalSimilarityService} into a read-only query surface for the goal explorer UI.
 *
 * <p>All methods read in-memory state (authoritative during runtime) with Neo4j used only
 * for per-criterion assessment history. The explorer is purely additive — no writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalExplorerService {

    /** Minimum similarity for a criterion pair to be surfaced as a synergy candidate. */
    private static final double SYNERGY_THRESHOLD = 0.5d;

    /** Upper bound on returned synergy candidates — keeps the UI payload bounded. */
    private static final int SYNERGY_LIMIT = 50;

    private final GoalPlannerService goalPlannerService;
    private final GoalSimilarityService goalSimilarityService;

    /** Optional — falls back to empty history when no Neo4j is wired (e.g. pure unit tests). */
    @Autowired(required = false)
    private OrchestrationSnapshotService snapshotService;

    /**
     * T4-AM: optional — write actions that need to seed signals on the carried-over cycle
     * context (e.g. pin-as-priority emitting REPLAN_REQUESTED) go through this bean. When not
     * wired (unit tests), those signal-injecting actions simply skip the signal step while
     * still applying the underlying planner state change.
     */
    @Autowired(required = false)
    private AutobuilderService autobuilderService;

    /**
     * T4-AM: optional — broadcasts a {@code TREE_CHANGED} event to SSE subscribers after any
     * write action so the live 3D graph reconciles without waiting for the next cycle. When
     * not wired (unit tests) the write action succeeds silently without broadcasting.
     */
    @Autowired(required = false)
    private GoalStateEventPublisher goalStateEventPublisher;

    /**
     * Optional — resolves absolute paths for the {@link #getLocations()} endpoint. Falls
     * back to {@code null}s when not wired (pure unit tests that don't need the folders).
     */
    @Autowired(required = false)
    private FileService fileService;

    /**
     * Optional — used by {@link #getCyclesForGoal(String)} to read mutation cycle metadata.
     * When absent, the per-goal cycle list returns empty.
     */
    @Autowired(required = false)
    private MutationSnapshotService mutationSnapshotService;

    /** Wire or override for tests. */
    public void setSnapshotService(OrchestrationSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    /** Wire or override for tests. */
    public void setFileService(FileService fileService) {
        this.fileService = fileService;
    }

    /** Wire or override for tests. */
    public void setAutobuilderService(AutobuilderService autobuilderService) {
        this.autobuilderService = autobuilderService;
    }

    /** Wire or override for tests. */
    public void setGoalStateEventPublisher(GoalStateEventPublisher publisher) {
        this.goalStateEventPublisher = publisher;
    }

    /** Wire or override for tests. */
    public void setMutationSnapshotService(MutationSnapshotService mutationSnapshotService) {
        this.mutationSnapshotService = mutationSnapshotService;
    }

    /**
     * Absolute filesystem locations for the project, its mutations/meta subfolders, and the
     * list of cycle names present under {@code mutations/}. The UI uses these to render
     * "Open in IDE" shortcuts. When {@link FileService} is not wired, returns all-null paths
     * and an empty cycle list.
     */
    public LocationsDto getLocations() {
        if (fileService == null) {
            return new LocationsDto(null, null, null, null, null, List.of());
        }
        Path root = fileService.getRootDir();
        Path mutations = root.resolve("mutations");
        Path meta = root.resolve("meta");
        Path goalStack = meta.resolve("GoalStack.json");
        Path logs = root.resolve("logs");

        List<String> cycles = new ArrayList<>();
        if (Files.isDirectory(mutations)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(mutations)) {
                for (Path p : stream) {
                    if (Files.isDirectory(p)) cycles.add(p.getFileName().toString());
                }
            } catch (Exception e) {
                log.debug("Failed to list mutation cycles: {}", e.getMessage());
            }
            Collections.sort(cycles, Collections.reverseOrder()); // newest first (lexicographic on cycle-<ms>)
        }

        return new LocationsDto(
                root.toString(),
                mutations.toString(),
                meta.toString(),
                goalStack.toString(),
                logs.toString(),
                cycles
        );
    }

    /**
     * Lists every mutation cycle that targeted the given goal (i.e. {@code currentSubGoalId} or
     * {@code currentGoalId} of the cycle's {@code meta.json} matches). Each entry carries the
     * file relative paths under {@code mutations/<cycleName>/} so the in-browser editor can
     * open them. Cycles without an active subgoal in their meta are excluded — they would not
     * be diagnostically useful per-goal anyway.
     *
     * <p>This replaces the global "mutation cycles" dump that was rendered for every node and
     * was largely redundant.</p>
     */
    public List<GoalCycleDto> getCyclesForGoal(String goalId) {
        if (fileService == null || mutationSnapshotService == null) return List.of();
        if (goalId == null || goalId.isBlank()) return List.of();

        Path mutations = fileService.getRootDir().resolve("mutations");
        if (!Files.isDirectory(mutations)) return List.of();

        List<GoalCycleDto> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mutations)) {
            for (Path cycleDir : stream) {
                if (!Files.isDirectory(cycleDir)) continue;
                Path meta = cycleDir.resolve("meta.json");
                if (!Files.isRegularFile(meta)) continue;

                CycleContextData ctx;
                try {
                    ctx = mutationSnapshotService.readMutationMetaResolved(meta);
                } catch (Exception e) {
                    log.debug("getCyclesForGoal: skipping malformed meta {}: {}", meta, e.getMessage());
                    continue;
                }
                if (ctx == null) continue;
                if (!Objects.equals(goalId, ctx.currentSubGoalId())
                        && !Objects.equals(goalId, ctx.currentGoalId())) continue;

                out.add(new GoalCycleDto(
                        cycleDir.getFileName().toString(),
                        ctx.merged(),
                        Files.getLastModifiedTime(cycleDir).toMillis(),
                        listCycleFiles(cycleDir)
                ));
            }
        } catch (Exception e) {
            log.debug("getCyclesForGoal failed: {}", e.getMessage());
        }
        // Newest first by mtime; ties broken lexicographically (cycle-<ms>).
        out.sort((a, b) -> {
            int cmp = Long.compare(
                    b.timestamp() != null ? b.timestamp() : 0L,
                    a.timestamp() != null ? a.timestamp() : 0L);
            return cmp != 0 ? cmp : b.cycleName().compareTo(a.cycleName());
        });
        return out;
    }

    private static List<String> listCycleFiles(Path cycleDir) {
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(cycleDir)) {
            walk.filter(Files::isRegularFile)
                    .map(p -> cycleDir.relativize(p).toString().replace('\\', '/'))
                    .filter(s -> !s.equals("meta.json"))
                    .sorted()
                    .forEach(files::add);
        } catch (Exception e) {
            // best effort
        }
        return files;
    }

    public GoalTreeNodeDto getGoalTree() {
        GoalEntity root = goalPlannerService.getRootGoal();
        if (root == null) return null;
        String currentId = currentSubGoalId();
        return buildTreeNode(root, currentId);
    }

    public GoalNodeDetailDto getGoalDetail(String goalId) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return null;
        String currentId = currentSubGoalId();
        List<CriterionStatusDto> criteria = buildCriteria(g);
        List<String> mergedFiles = asStringList(g.getMetadata().get("mergedFiles"));
        List<String> ancestry = buildAncestry(g);
        String planRationale = goalPlannerService.getPlanRationale(g);
        return new GoalNodeDetailDto(
                g.getId(),
                g.getDescription(),
                g.getType() != null ? g.getType().name() : null,
                g.getStatus() != null ? g.getStatus().name() : null,
                g.getRole(),
                g.getParentId(),
                g.getCreated(),
                g.getCompleted(),
                g.getGivenUp(),
                g.getResolution(),
                g.getMergeCount(),
                goalPlannerService.getGoalMetadataInt(g, "cumulativeStagnatingCycles"),
                readInt(g.getMetadata().get("planningRunId")),
                Objects.equals(g.getId(), currentId),
                criteria,
                mergedFiles,
                ancestry,
                planRationale
        );
    }

    public List<CriterionStatusDto> getCriteria(String goalId) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return List.of();
        return buildCriteria(g);
    }

    /**
     * Finds criteria on other subgoals that are semantically similar to the given goal's
     * criteria. The source goal itself is excluded. Results are sorted by similarity desc
     * and capped at {@value #SYNERGY_LIMIT}.
     */
    public List<SynergyCandidateDto> findSynergies(String sourceGoalId) {
        GoalEntity source = goalPlannerService.getGoalById(sourceGoalId);
        if (source == null) return List.of();
        List<String> sourceCriteria = asStringList(source.getMetadata().get("acceptanceCriteria"));
        if (sourceCriteria.isEmpty()) return List.of();

        List<SynergyCandidateDto> out = new ArrayList<>();
        for (GoalEntity other : goalPlannerService.getAllGoals()) {
            if (Objects.equals(other.getId(), sourceGoalId)) continue;
            List<String> otherCriteria = asStringList(other.getMetadata().get("acceptanceCriteria"));
            if (otherCriteria.isEmpty()) continue;
            for (int i = 0; i < sourceCriteria.size(); i++) {
                String src = sourceCriteria.get(i);
                if (src == null || src.isBlank()) continue;
                for (int j = 0; j < otherCriteria.size(); j++) {
                    String dst = otherCriteria.get(j);
                    if (dst == null || dst.isBlank()) continue;
                    float sim = goalSimilarityService.similarity(src, dst);
                    if (sim < SYNERGY_THRESHOLD) continue;
                    out.add(new SynergyCandidateDto(
                            sourceGoalId, i + 1, src,
                            other.getId(), other.getDescription(),
                            other.getStatus() != null ? other.getStatus().name() : null,
                            j + 1, dst,
                            sim
                    ));
                }
            }
        }
        out.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return out.size() > SYNERGY_LIMIT ? out.subList(0, SYNERGY_LIMIT) : out;
    }

    /**
     * Cross-goal file overlay: inverted index of merged filename → list of subgoal IDs
     * that recorded that file. Only files touched by two or more subgoals are returned —
     * these are the candidates for redundant or conflicting evolutions.
     */
    public List<FileOverlayDto> getFileOverlay() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        for (GoalEntity g : goalPlannerService.getAllGoals()) {
            List<String> files = asStringList(g.getMetadata().get("mergedFiles"));
            for (String f : files) {
                if (f == null || f.isBlank()) continue;
                index.computeIfAbsent(f, k -> new ArrayList<>()).add(g.getId());
            }
        }
        List<FileOverlayDto> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : index.entrySet()) {
            if (e.getValue().size() < 2) continue;
            result.add(new FileOverlayDto(e.getKey(), List.copyOf(e.getValue())));
        }
        return result;
    }

    // -----------------------------------------------------------------------------------
    // T4-AM: write actions
    // -----------------------------------------------------------------------------------

    /**
     * Marks a single acceptance criterion on the given subgoal as cumulatively met. This flips
     * the criterion's entry in {@code criteriaPassStatus} to {@code true} and clears its
     * consecutive-fail streak. Does not rewrite per-cycle evaluator history — it only banks
     * the criterion as satisfied so downstream fulfilment logic (e.g. {@code allCriteriaDefinedAndBanked})
     * treats it as green on the next cycle.
     */
    public ActionResultDto markCriterionMet(String goalId, int criterionIndex, String reason) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return ActionResultDto.fail("Goal not found: " + goalId);
        List<String> criteria = asStringList(g.getMetadata().get("acceptanceCriteria"));
        if (criterionIndex < 1 || criterionIndex > criteria.size()) {
            return ActionResultDto.fail("Criterion index out of range: " + criterionIndex);
        }

        Map<Integer, Boolean> status = new LinkedHashMap<>(goalPlannerService.getCriteriaPassStatus(g));
        status.put(criterionIndex, true);
        goalPlannerService.saveCriteriaPassStatus(goalId, status);

        Map<Integer, Integer> streak = new LinkedHashMap<>(goalPlannerService.getCriteriaFailStreak(g));
        streak.remove(criterionIndex);
        goalPlannerService.saveCriteriaFailStreak(goalId, streak);

        log.info("T4-AM: markCriterionMet goalId={} index={} reason={}", goalId, criterionIndex, reason);
        broadcastTreeChanged();
        return ActionResultDto.ok("Criterion " + criterionIndex + " marked met.", goalId);
    }

    /** Replaces the text of an existing acceptance criterion (1-based index). */
    public ActionResultDto updateCriterionText(String goalId, int criterionIndex, String text) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return ActionResultDto.fail("Goal not found: " + goalId);
        if (text == null || text.isBlank()) return ActionResultDto.fail("Criterion text must not be blank");
        List<String> criteria = new ArrayList<>(asStringList(g.getMetadata().get("acceptanceCriteria")));
        if (criterionIndex < 1 || criterionIndex > criteria.size()) {
            return ActionResultDto.fail("Criterion index out of range: " + criterionIndex);
        }
        criteria.set(criterionIndex - 1, text.trim());
        goalPlannerService.setAcceptanceCriteria(goalId, criteria);
        log.info("T5-BB: updateCriterionText goalId={} index={}", goalId, criterionIndex);
        broadcastTreeChanged();
        return ActionResultDto.ok("Criterion " + criterionIndex + " updated.", goalId);
    }

    /**
     * Removes an acceptance criterion by 1-based index. Resets pass/fail history because
     * the remaining criteria shift down and old indices no longer map correctly.
     */
    public ActionResultDto deleteCriterion(String goalId, int criterionIndex) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return ActionResultDto.fail("Goal not found: " + goalId);
        List<String> criteria = new ArrayList<>(asStringList(g.getMetadata().get("acceptanceCriteria")));
        if (criterionIndex < 1 || criterionIndex > criteria.size()) {
            return ActionResultDto.fail("Criterion index out of range: " + criterionIndex);
        }
        criteria.remove(criterionIndex - 1);
        goalPlannerService.setAcceptanceCriteria(goalId, criteria);
        goalPlannerService.saveCriteriaPassStatus(goalId, Map.of());
        goalPlannerService.saveCriteriaFailStreak(goalId, Map.of());
        log.info("T5-BB: deleteCriterion goalId={} index={}", goalId, criterionIndex);
        broadcastTreeChanged();
        return ActionResultDto.ok("Criterion " + criterionIndex + " deleted (pass/fail history reset).", goalId);
    }

    /** Appends a new acceptance criterion to the goal's list. */
    public ActionResultDto addCriterion(String goalId, String text) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return ActionResultDto.fail("Goal not found: " + goalId);
        if (text == null || text.isBlank()) return ActionResultDto.fail("Criterion text must not be blank");
        List<String> criteria = new ArrayList<>(asStringList(g.getMetadata().get("acceptanceCriteria")));
        criteria.add(text.trim());
        goalPlannerService.setAcceptanceCriteria(goalId, criteria);
        log.info("T5-BB: addCriterion goalId={} newIndex={}", goalId, criteria.size());
        broadcastTreeChanged();
        return ActionResultDto.ok("Criterion " + criteria.size() + " added.", goalId);
    }

    /**
     * Pins a subgoal as the current work target. Activates the subgoal (no-op if already ACTIVE),
     * sets it as {@code currentSubGoalId}, and emits a {@link BasicSignals#REPLAN_REQUESTED}
     * signal so the next cycle's planner re-evaluates siblings in light of the new target.
     */
    public ActionResultDto pinAsPriority(String goalId, String reason) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return ActionResultDto.fail("Goal not found: " + goalId);
        if (g.getType() != GoalType.SUBGOAL) {
            return ActionResultDto.fail("Only SUBGOAL nodes can be pinned (type=" + g.getType() + ")");
        }

        boolean activated = goalPlannerService.activateSubGoal(goalId, reason != null ? reason : "pinned via UI");
        if (!activated) return ActionResultDto.fail("activateSubGoal returned false for " + goalId);

        if (autobuilderService != null) {
            autobuilderService.injectSignal(
                    Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.25, "user-pin")
            );
        }
        log.info("T4-AM: pinAsPriority goalId={} reason={}", goalId, reason);
        broadcastTreeChanged();
        return ActionResultDto.ok("Pinned as current subgoal.", goalId);
    }

    /**
     * Creates a new SUBGOAL under the given parent. Optionally seeds acceptance criteria and
     * pins the injected subgoal as current. Fails when the parent cannot be found.
     */
    public ActionResultDto injectSubgoal(String parentId, GoalInjectSubgoalRequest req) {
        if (req == null || req.description() == null || req.description().isBlank()) {
            return ActionResultDto.fail("description must not be blank");
        }
        GoalEntity parent = goalPlannerService.getGoalById(parentId);
        if (parent == null) return ActionResultDto.fail("Parent goal not found: " + parentId);

        String newId = "user-" + UUID.randomUUID();
        String role = (req.role() != null && !req.role().isBlank()) ? req.role() : "user";
        try {
            goalPlannerService.addSubGoal(newId, req.description().trim(), req.setAsCurrent(), parentId, role, null);
        } catch (RuntimeException ex) {
            return ActionResultDto.fail("addSubGoal failed: " + ex.getMessage());
        }

        if (req.criteria() != null && !req.criteria().isEmpty()) {
            List<String> cleaned = new ArrayList<>();
            for (String c : req.criteria()) {
                if (c != null && !c.isBlank()) cleaned.add(c.trim());
            }
            if (!cleaned.isEmpty()) {
                goalPlannerService.setAcceptanceCriteria(newId, cleaned);
            }
        }

        if (req.setAsCurrent() && autobuilderService != null) {
            autobuilderService.injectSignal(
                    Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.25, "user-inject-current")
            );
        }

        log.info("T4-AM: injectSubgoal parentId={} newId={} description='{}' setAsCurrent={} criteria={}",
                parentId, newId, req.description(), req.setAsCurrent(),
                req.criteria() != null ? req.criteria().size() : 0);
        broadcastTreeChanged();
        return ActionResultDto.ok("Subgoal injected.", newId);
    }

    /**
     * Forces a goal to the {@code GIVEN_UP} terminal state. Applies the progress transition
     * via the planner so the usual cascading / resolution machinery runs. The given resolution
     * string (falls back to a generic message when null/blank) is persisted as the goal's
     * {@code resolution}.
     */
    public ActionResultDto forceGivenUp(String goalId, String resolution) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return ActionResultDto.fail("Goal not found: " + goalId);
        if (g.getStatus() == GoalStatus.GIVEN_UP) {
            return ActionResultDto.fail("Goal already GIVEN_UP");
        }
        String reason = (resolution != null && !resolution.isBlank())
                ? resolution.trim()
                : "user-initiated force GIVEN_UP";
        goalPlannerService.applyGoalProgress(goalId, GoalProgressState.GIVEN_UP, reason);
        log.info("T4-AM: forceGivenUp goalId={} reason={}", goalId, reason);
        broadcastTreeChanged();
        return ActionResultDto.ok("Goal marked GIVEN_UP.", goalId);
    }

    /**
     * Reverses a terminal (COMPLETED / GIVEN_UP) goal back to ACTIVE and wakes the loop.
     * Delegates to the planner's {@code reactivateGoal} (which cascades up to terminal
     * ancestors), then — if anything was actually flipped — clears {@code SYSTEM_PAUSED}
     * and {@code GOAL_EXHAUSTED} on the cycle context so the scheduler picks the work back
     * up. When {@code setAsCurrent} is true and the goal is a SUBGOAL the planner's current
     * pointer is moved to it; either way a {@code REPLAN_REQUESTED} signal is emitted so the
     * next cycle rethinks the plan.
     */
    public ActionResultDto reactivate(String goalId, boolean setAsCurrent) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return ActionResultDto.fail("Goal not found: " + goalId);

        List<String> flipped = goalPlannerService.reactivateGoal(goalId);
        boolean wasTerminal = !flipped.isEmpty();

        if (setAsCurrent && g.getType() == GoalType.SUBGOAL) {
            boolean pinned = goalPlannerService.activateSubGoal(goalId, "reactivate via UI");
            if (!pinned) {
                log.warn("reactivate: activateSubGoal returned false for {} — reactivation applied but not pinned", goalId);
            }
        }

        if (autobuilderService != null) {
            if (wasTerminal) autobuilderService.clearHaltSignals();
            autobuilderService.injectSignal(
                    Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.25, "user-reactivate")
            );
        }

        log.info("T4-AM: reactivate goalId={} setAsCurrent={} flipped={}", goalId, setAsCurrent, flipped);
        broadcastTreeChanged();
        String msg = wasTerminal
                ? ("Reactivated (" + flipped.size() + " node" + (flipped.size() == 1 ? "" : "s") + " flipped to ACTIVE).")
                : "Already ACTIVE — replan signalled.";
        return ActionResultDto.ok(msg, goalId);
    }

    /**
     * Updates a goal's description in-place. Persists via {@code saveGoalMetadata} so the
     * change reaches Neo4j. Intended for quick wording fixes (e.g. clarifying an ambiguous
     * subgoal spec) without invalidating the criteria or progress history.
     */
    public ActionResultDto updateDescription(String goalId, String description) {
        if (description == null || description.isBlank()) {
            return ActionResultDto.fail("description must not be blank");
        }
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return ActionResultDto.fail("Goal not found: " + goalId);
        g.setDescription(description.trim());
        goalPlannerService.saveGoalMetadata(g);
        log.info("T4-AM: updateDescription goalId={} newLen={}", goalId, description.length());
        broadcastTreeChanged();
        return ActionResultDto.ok("Description updated.", goalId);
    }

    /** Invokes the publisher's TREE_CHANGED broadcast if wired; best-effort. */
    private void broadcastTreeChanged() {
        if (goalStateEventPublisher == null) return;
        try {
            goalStateEventPublisher.publishTreeChanged(
                    autobuilderService != null
                            ? autobuilderService.getContext().orElse(null)
                            : null
            );
        } catch (Exception e) {
            log.debug("TREE_CHANGED broadcast failed (ignoring): {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------

    private GoalTreeNodeDto buildTreeNode(GoalEntity g, String currentId) {
        List<GoalEntity> children = goalPlannerService.getSubGoals(g.getId());
        List<GoalTreeNodeDto> childDtos = new ArrayList<>(children.size());
        for (GoalEntity c : children) {
            childDtos.add(buildTreeNode(c, currentId));
        }
        List<String> criteria = asStringList(g.getMetadata().get("acceptanceCriteria"));
        Map<Integer, Boolean> passStatus = goalPlannerService.getCriteriaPassStatus(g);
        int met = 0;
        for (int i = 1; i <= criteria.size(); i++) {
            if (Boolean.TRUE.equals(passStatus.get(i))) met++;
        }
        return new GoalTreeNodeDto(
                g.getId(),
                g.getDescription(),
                g.getType() != null ? g.getType().name() : null,
                g.getStatus() != null ? g.getStatus().name() : null,
                g.getRole(),
                g.getCreated(),
                g.getCompleted(),
                g.getGivenUp(),
                g.getMergeCount(),
                goalPlannerService.getGoalMetadataInt(g, "cumulativeStagnatingCycles"),
                readInt(g.getMetadata().get("planningRunId")),
                criteria.size(),
                met,
                Objects.equals(g.getId(), currentId),
                childDtos
        );
    }

    private List<CriterionStatusDto> buildCriteria(GoalEntity g) {
        List<String> criteria = asStringList(g.getMetadata().get("acceptanceCriteria"));
        if (criteria.isEmpty()) return List.of();

        Map<Integer, Boolean> passStatus = goalPlannerService.getCriteriaPassStatus(g);
        Map<Integer, Integer> failStreaks = goalPlannerService.getCriteriaFailStreak(g);
        Map<Integer, OrchestrationSnapshot.CriterionProgress> progressByIndex = fetchProgress(g.getId());

        List<CriterionStatusDto> out = new ArrayList<>(criteria.size());
        for (int i = 0; i < criteria.size(); i++) {
            int idx = i + 1;
            String text = criteria.get(i);
            boolean cumulativePassed = Boolean.TRUE.equals(passStatus.get(idx));
            int failStreak = failStreaks.getOrDefault(idx, 0);
            OrchestrationSnapshot.CriterionProgress progress = progressByIndex.get(idx);
            boolean neverAssessed = progress == null || progress.neverAssessed();
            Boolean lastPassed = progress == null ? null : progress.lastPassed();
            boolean everPassed = progress != null && progress.everPassed();
            int consecutiveFailCount = progress == null ? 0 : progress.consecutiveFailCount();
            out.add(new CriterionStatusDto(
                    idx, text,
                    cumulativePassed,
                    failStreak,
                    neverAssessed,
                    lastPassed,
                    everPassed,
                    consecutiveFailCount
            ));
        }
        return out;
    }

    private Map<Integer, OrchestrationSnapshot.CriterionProgress> fetchProgress(String goalId) {
        if (snapshotService == null) return Collections.emptyMap();
        try {
            List<OrchestrationSnapshot.CriterionProgress> rows = snapshotService.fetchCriteriaProgress(goalId);
            Map<Integer, OrchestrationSnapshot.CriterionProgress> map = new LinkedHashMap<>();
            for (OrchestrationSnapshot.CriterionProgress p : rows) {
                map.put(p.criterionIndex(), p);
            }
            return map;
        } catch (Exception e) {
            log.debug("fetchCriteriaProgress failed for goal {}: {}", goalId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<String> buildAncestry(GoalEntity g) {
        List<String> chain = new ArrayList<>();
        String cursor = g.getParentId();
        int guard = 0;
        while (cursor != null && guard++ < 32) {
            chain.add(cursor);
            GoalEntity parent = goalPlannerService.getGoalById(cursor);
            if (parent == null) break;
            cursor = parent.getParentId();
        }
        Collections.reverse(chain);
        return chain;
    }

    private String currentSubGoalId() {
        // T1-FD: peek, never getCurrentSubGoal — the full accessor runs recovery + T1-CN
        // closure (with LLM calls), which a UI tree refresh must never trigger.
        GoalEntity curr = goalPlannerService.peekCurrentSubGoal();
        return curr != null ? curr.getId() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object raw) {
        if (!(raw instanceof List<?> l) || l.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o == null) continue;
            out.add(o.toString());
        }
        return out;
    }

    private static Integer readInt(Object raw) {
        return raw instanceof Number n ? n.intValue() : null;
    }
}
