package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.core.stuck.Recurrence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T6-T: the objective met by the project's own tests, and the criteria still unconfirmed no longer moving -- then the
 * root closes, with those criteria named as open, instead of waiting for the deadline.
 *
 * <p><b>The gap, from the field.</b> Rule-extraction run 2026-10-18_19-33-28 (band 4): the loop's own test run found
 * the whole suite passing at minute 41 -- the objective the seed states ("every test ... passes, and the whole suite
 * passes"). Eight criteria read from the ticket's text stayed unconfirmed; the work item raised for them was refused
 * seventeen times with the same eight, and the run ended at its deadline with the objective met and no closure.
 *
 * <p><b>When.</b> Two facts, each the loop's own: its test gate ran the suite on the tree as merged and nothing
 * failed; and the same set of unconfirmed criteria was refused persistently -- {@link Recurrence}, the run's own ratios.
 * The criteria are not dropped: they are named in the root's closing reason as open, for the operator to judge.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "per-run record of this run's own refusals; a restored run starts it afresh")
public class ObjectiveMetClosure {

    private final Recurrence recurrence;
    private final Set<String> stalled = ConcurrentHashMap.newKeySet();
    /** Refusals per (criteria, tree): the same criteria refused on a tree that has not changed since. */
    private final java.util.Map<String, Integer> refusalsOnTree = new ConcurrentHashMap<>();

    @Lazy
    @Autowired(required = false)
    private TestRunGate testRunGate;

    public ObjectiveMetClosure() {
        this(Recurrence.ofThisRun());
    }

    ObjectiveMetClosure(Recurrence recurrence) {
        this.recurrence = recurrence;
    }

    void setTestRunGate(TestRunGate gate) {
        this.testRunGate = gate;
    }

    /** Whether the loop's own test run has run the suite on the tree as merged, with no test failing. */
    public boolean objectiveMet() {
        return testRunGate != null && testRunGate.treeTests() == TestRunGate.TreeTests.RAN
                && testRunGate.treeFailing().map(Set::isEmpty).orElse(false);
    }

    /** A work item's criteria were refused again, as {@code unconfirmed}; true when that refusal has become persistent. */
    public boolean refused(Collection<String> unconfirmed) {
        return refused(unconfirmed, System.currentTimeMillis());
    }

    synchronized boolean refused(Collection<String> unconfirmed, long at) {
        if (unconfirmed == null || unconfirmed.isEmpty()) return false;
        Set<String> set = new TreeSet<>(unconfirmed);
        boolean persistent = recurrence.observe(String.join("\n", set), at);
        if (persistent && stalled.addAll(set)) {
            log.warn("T6-T: GRIND -- the same {} criteria are refused again and again while nothing confirms them: {}",
                    set.size(), String.join("; ", set));
        }
        return persistent;
    }

    /**
     * T6-T: the same criteria refused again on a tree that has not changed -- true from the second refusal. No new
     * evidence can arrive while the tree stays as it is, so waiting for persistence by the run's ratios only lets the
     * loop spin: rule-extraction run 2026-10-19_12-46-09 refused one cached verdict thirteen times in five seconds,
     * read that as a grind, rewound and halted with the objective met.
     */
    public boolean refusedOnUnchangedTree(Collection<String> unconfirmed, String treeDigest) {
        if (unconfirmed == null || unconfirmed.isEmpty() || treeDigest == null) return false;
        String key = String.join("\n", new TreeSet<>(unconfirmed)) + "@" + treeDigest;
        return refusalsOnTree.merge(key, 1, Integer::sum) >= 2;
    }

    /** The criteria refused on an unchanged tree, a second reading not confirming them: stalled from now on. */
    public void stalledOnUnchangedTree(Collection<String> unconfirmed) {
        if (unconfirmed != null && stalled.addAll(unconfirmed)) {
            log.warn("T6-T: GRIND -- the same {} criteria are refused again on a tree that has not changed, and a second"
                    + " reading does not confirm them: {}", unconfirmed.size(), String.join("; ", unconfirmed));
        }
    }

    /** The criteria stalled so far -- refused persistently or on an unchanged tree -- in a stable order. */
    public java.util.List<String> stalledCriteria() {
        return java.util.List.copyOf(new TreeSet<>(stalled));
    }

    /** Whether every one of {@code unconfirmed} was among criteria refused persistently. */
    public boolean allStalled(Collection<String> unconfirmed) {
        return unconfirmed != null && !unconfirmed.isEmpty() && stalled.containsAll(unconfirmed);
    }

    /** The closing reason for a root closed on its objective, the stalled criteria named as open. */
    public static String reason(List<String> open) {
        return "objective met by the project's own tests (the whole suite passes on the tree as merged); open from the"
                + " ticket's text, refused again and again with nothing moving: " + String.join("; ", open);
    }

    /** The closing reason when what is left asks only for files the tree does not have. */
    public static String outsideTreeReason(List<String> outside) {
        return "objective met by the project's own tests (the whole suite passes on the tree as merged); every other"
                + " criterion confirmed; open, as they cannot be judged in this tree: " + String.join("; ", outside);
    }
}
