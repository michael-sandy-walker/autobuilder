package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T6-T: a file the model rewrote whole, to no effect on the failing tests, is changed by edits next -- until a change
 * to it makes the tests better.
 *
 * <p><b>The stall, from the field.</b> Ladder-rows run 2026-10-14_18-10-40 (21/28): after its first merge the model
 * sent 43 more versions of the one class the failing tests were about. Twelve were the file as it stood; 24 of the
 * other 31 redid the class (a median of 42% of its lines replaced) while the trace in front of the model named one
 * fault at a time (the annotation of the method above read for the method below). Each rewrite fixed one thing and
 * moved another, and the failing count stood at seven for 113 minutes. Only two answers were edit blocks.
 *
 * <p><b>What counts as a rewrite.</b> A version that replaces more than a quarter of the file's lines. Measured on the
 * 31 changes of that run: the ones that edited a part replaced 3 to 19% of the lines, the ones that redid the class
 * 26 to 54% (a median of 42%), and nothing fell between; "more than half" would have caught 2 of the 31.
 *
 * <p><b>What it holds and for how long.</b> A file whose last tested version rewrote it and did not make the tests
 * better is held: the next answer that rewrites it again is asked once more for edits instead. It is
 * released by a change to it that makes the tests better, and, so that a design that really must be redone is not
 * barred for good, after it has refused {@link #RELEASE_AFTER} answers.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "per-run record of this run's own attempts, as the stuck ledger; a restored run starts it afresh")
public class RewriteBrake {

    /** Two whole calls of the model, each asked again once: past that, the file is the model's to rewrite. */
    static final int RELEASE_AFTER = 4;

    private final Map<String, Integer> held = new LinkedHashMap<>();

    /** A candidate was tested against the tree it was written over. */
    public synchronized void tested(Map<String, String> tree, List<FileMutation> files, boolean better) {
        if (files == null) return;
        for (FileMutation f : files) {
            if (f == null || f.path() == null) continue;
            if (better) {
                if (held.remove(f.path()) != null) log.info("T6-T: {} is changed by any means again -- a change to it made the tests better", f.path());
                continue;
            }
            String before = tree == null ? null : tree.get(f.path());
            if (before != null && rewrites(before, f.content()) && !held.containsKey(f.path())) {
                held.put(f.path(), 0);
                log.info("T6-T: {} was rewritten whole and the tests were no better -- edits next", f.path());
            }
        }
    }

    /** The files held now. */
    public synchronized Set<String> held() {
        return Set.copyOf(held.keySet());
    }

    /** Whether {@code after}, written over {@code before} at {@code path}, is a rewrite of a held file. */
    public synchronized boolean holds(String path, String before, String after) {
        return held.containsKey(path) && before != null && rewrites(before, after);
    }

    /** One answer was asked again for rewriting {@code path}; past {@link #RELEASE_AFTER} the file is released. */
    public synchronized void refused(String path) {
        Integer n = held.get(path);
        if (n == null) return;
        if (n + 1 >= RELEASE_AFTER) {
            held.remove(path);
            log.info("T6-T: {} -- {} rewrites asked again in a row; the file is the model's to rewrite again", path, RELEASE_AFTER);
        } else {
            held.put(path, n + 1);
        }
    }

    /** Whether more than a quarter of {@code before}'s lines are gone from {@code after}. */
    static boolean rewrites(String before, String after) {
        if (before == null || after == null) return false;
        List<String> old = before.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (old.isEmpty()) return false;
        Set<String> kept = new HashSet<>(after.lines().map(String::strip).toList());
        long gone = old.stream().filter(l -> !kept.contains(l)).count();
        return gone * 4 > old.size();
    }
}
