package com.walpro.autobuilder.analysis.problem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public record ProblemContext(
        List<ProblemEntry> open,
        List<ProblemEntry> resolved
) {

    public static ProblemContext empty() {
        return new ProblemContext(List.of(), List.of());
    }

    public boolean hasBlockingProblemsFor(String goalId, String subGoalId) {
        if (open == null || open.isEmpty()) return false;
        for (ProblemEntry p : open) {
            if (p != null && p.isBlocking() && p.matchesScope(goalId, subGoalId)) return true;
        }
        return false;
    }

    public ProblemEntry findOpenById(String id) {
        if (id == null || id.isBlank() || open == null) return null;
        for (ProblemEntry p : open) {
            if (p != null && id.equals(p.id())) return p;
        }
        return null;
    }

    public ProblemEntry findResolvedById(String id) {
        if (id == null || id.isBlank() || resolved == null) return null;
        for (ProblemEntry p : resolved) {
            if (p != null && id.equals(p.id())) return p;
        }
        return null;
    }

    /**
     * Upsert into OPEN:
     * - If exists in open: merge (preserve createdAt/firstSeenCycleId, update updatedAt/lastSeenCycleId, union keywords/evidence)
     * - If exists in resolved: remove from resolved and reopen (merge)
     * - If new: ensure createdAt/updatedAt and firstSeen/lastSeen stamps are set
     */
    public ProblemContext upsertOpen(ProblemEntry entry) {
        Objects.requireNonNull(entry, "entry");

        Instant now = Instant.now();

        List<ProblemEntry> nextOpen = new ArrayList<>(open == null ? List.of() : open);
        List<ProblemEntry> nextResolved = new ArrayList<>(resolved == null ? List.of() : resolved);

        // If it was previously resolved, remove it from resolved and treat as reopen
        ProblemEntry previouslyResolved = null;
        for (int i = 0; i < nextResolved.size(); i++) {
            ProblemEntry r = nextResolved.get(i);
            if (r != null && Objects.equals(r.id(), entry.id())) {
                previouslyResolved = r;
                nextResolved.remove(i);
                break;
            }
        }

        // Merge into existing OPEN
        for (int i = 0; i < nextOpen.size(); i++) {
            ProblemEntry existing = nextOpen.get(i);
            if (existing != null && Objects.equals(existing.id(), entry.id())) {
                nextOpen.set(i, merge(existing, entry, now));
                return new ProblemContext(List.copyOf(nextOpen), List.copyOf(nextResolved));
            }
        }

        // If reopening from resolved, merge the resolved version into incoming (keeps original timestamps/evidence)
        ProblemEntry toInsert = (previouslyResolved != null)
                ? merge(previouslyResolved, entry, now)
                : normalizeNew(entry, now);

        nextOpen.add(toInsert);
        return new ProblemContext(List.copyOf(nextOpen), List.copyOf(nextResolved));
    }

    /**
     * Resolve a specific OPEN entry by id. No-op if not found in OPEN.
     */
    public ProblemContext resolveById(String id, String note) {
        if (id == null || id.isBlank()) return this;

        Instant now = Instant.now();

        List<ProblemEntry> nextOpen = new ArrayList<>(open == null ? List.of() : open);
        List<ProblemEntry> nextResolved = new ArrayList<>(resolved == null ? List.of() : resolved);

        for (int i = 0; i < nextOpen.size(); i++) {
            ProblemEntry p = nextOpen.get(i);
            if (p != null && Objects.equals(p.id(), id)) {
                nextOpen.remove(i);

                ProblemEntry resolvedEntry = p
                        .withStatus(ProblemStatus.RESOLVED)
                        .withUpdatedAt(now);

                if (note != null && !note.isBlank()) {
                    List<String> notes = new ArrayList<>(resolvedEntry.evidenceNotes() == null ? List.of() : resolvedEntry.evidenceNotes());
                    notes.add(note.trim());
                    resolvedEntry = resolvedEntry.withEvidenceNotes(List.copyOf(notes));
                }

                nextResolved = upsertResolvedList(nextResolved, resolvedEntry, now);
                return new ProblemContext(List.copyOf(nextOpen), List.copyOf(nextResolved));
            }
        }

        return this;
    }

    /**
     * Resolve all OPEN entries matching a predicate.
     */
    public ProblemContext resolveIf(Predicate<ProblemEntry> predicate, String note) {
        if (predicate == null || open == null || open.isEmpty()) return this;

        ProblemContext out = this;
        for (ProblemEntry p : open) {
            if (p != null && predicate.test(p)) {
                out = out.resolveById(p.id(), note);
            }
        }
        return out;
    }

    /**
     * Move a RESOLVED entry back to OPEN (useful when a resolved pattern reappears).
     */
    public ProblemContext reopenById(String id, String note) {
        if (id == null || id.isBlank() || resolved == null || resolved.isEmpty()) return this;

        Instant now = Instant.now();

        List<ProblemEntry> nextOpen = new ArrayList<>(open == null ? List.of() : open);
        List<ProblemEntry> nextResolved = new ArrayList<>(resolved);

        for (int i = 0; i < nextResolved.size(); i++) {
            ProblemEntry r = nextResolved.get(i);
            if (r != null && Objects.equals(r.id(), id)) {
                nextResolved.remove(i);

                ProblemEntry reopened = r
                        .withStatus(ProblemStatus.OPEN)
                        .withUpdatedAt(now)
                        // keep createdAt and firstSeenCycleId, but refresh lastSeenCycleId if caller set it later via detector
                        ;

                if (note != null && !note.isBlank()) {
                    List<String> notes = new ArrayList<>(reopened.evidenceNotes() == null ? List.of() : reopened.evidenceNotes());
                    notes.add(note.trim());
                    reopened = reopened.withEvidenceNotes(List.copyOf(notes));
                }

                // insert into open with merge semantics
                ProblemContext tmp = new ProblemContext(List.copyOf(nextOpen), List.copyOf(nextResolved));
                return tmp.upsertOpen(reopened);
            }
        }

        return this;
    }

    /**
     * Keep the context bounded. Recommended to call before persisting or before prompting:
     * - Cap open and resolved sizes
     * - Sort by severity desc, then updatedAt desc
     */
    public ProblemContext compact(int maxOpen, int maxResolved) {
        List<ProblemEntry> o = new ArrayList<>(open == null ? List.of() : open);
        List<ProblemEntry> r = new ArrayList<>(resolved == null ? List.of() : resolved);

        Comparator<ProblemEntry> cmp = Comparator
                .comparing((ProblemEntry p) -> p == null || p.severity() == null ? 0 : severityRank(p.severity()))
                .reversed()
                .thenComparing(p -> p == null ? Instant.EPOCH : safeInstant(p.updatedAt()), Comparator.reverseOrder());

        o.sort(cmp);
        r.sort(cmp);

        if (maxOpen > 0 && o.size() > maxOpen) o = o.subList(0, maxOpen);
        if (maxResolved > 0 && r.size() > maxResolved) r = r.subList(0, maxResolved);

        return new ProblemContext(List.copyOf(o), List.copyOf(r));
    }

    // ---- internals ----

    private static int severityRank(ProblemSeverity s) {
        return switch (s) {
            case BLOCKER -> 3;
            case WARNING -> 2;
            case INFO -> 1;
        };
    }

    private static Instant safeInstant(Instant t) {
        return t == null ? Instant.EPOCH : t;
    }

    private static ProblemEntry normalizeNew(ProblemEntry e, Instant now) {
        ProblemEntry out = e;

        if (out.createdAt() == null) out = out.withCreatedAt(now);
        if (out.updatedAt() == null) out = out.withUpdatedAt(now);

        if (out.firstSeenCycleId() == null || out.firstSeenCycleId().isBlank()) {
            out = out.withFirstSeenCycleId(out.lastSeenCycleId());
        }
        if (out.lastSeenCycleId() == null || out.lastSeenCycleId().isBlank()) {
            out = out.withLastSeenCycleId(out.firstSeenCycleId());
        }

        return out;
    }

    /**
     * Merge incoming into existing:
     * - preserve createdAt + firstSeenCycleId from existing when present
     * - set updatedAt to now
     * - set lastSeenCycleId from incoming if present, otherwise keep existing
     * - union keywords and evidence lists
     */
    private static ProblemEntry merge(ProblemEntry existing, ProblemEntry incoming, Instant now) {
        ProblemEntry out = incoming;

        // createdAt: preserve earliest known
        Instant created = existing.createdAt() != null ? existing.createdAt() : incoming.createdAt();
        out = out.withCreatedAt(created);

        // firstSeenCycleId: preserve existing if present
        String firstSeenCycle = (existing.firstSeenCycleId() != null && !existing.firstSeenCycleId().isBlank())
                ? existing.firstSeenCycleId()
                : incoming.firstSeenCycleId();
        out = out.withFirstSeenCycleId(firstSeenCycle);

        // lastSeenCycleId: prefer incoming if present, else keep existing
        String lastSeenCycle = (incoming.lastSeenCycleId() != null && !incoming.lastSeenCycleId().isBlank())
                ? incoming.lastSeenCycleId()
                : existing.lastSeenCycleId();
        out = out.withLastSeenCycleId(lastSeenCycle);

        // updatedAt always advances
        out = out.withUpdatedAt(now);

        // union keywords
        Set<String> mergedKeywords = new LinkedHashSet<>();
        if (existing.keywords() != null) mergedKeywords.addAll(existing.keywords());
        if (incoming.keywords() != null) mergedKeywords.addAll(incoming.keywords());
        out = out.withKeywords(Set.copyOf(mergedKeywords));

        // union evidence cycles
        List<String> mergedCycles = mergeList(existing.evidenceCycles(), incoming.evidenceCycles(), 50);
        out = out.withEvidenceCycles(mergedCycles);

        // union evidence notes
        List<String> mergedNotes = mergeList(existing.evidenceNotes(), incoming.evidenceNotes(), 50);
        out = out.withEvidenceNotes(mergedNotes);

        return out;
    }

    private static List<String> mergeList(List<String> a, List<String> b, int cap) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (a != null) for (String s : a) if (s != null && !s.isBlank()) set.add(s.trim());
        if (b != null) for (String s : b) if (s != null && !s.isBlank()) set.add(s.trim());

        List<String> out = new ArrayList<>(set);
        if (cap > 0 && out.size() > cap) {
            out = out.subList(0, cap);
        }
        return List.copyOf(out);
    }

    private static List<ProblemEntry> upsertResolvedList(List<ProblemEntry> resolved, ProblemEntry entry, Instant now) {
        for (int i = 0; i < resolved.size(); i++) {
            ProblemEntry existing = resolved.get(i);
            if (existing != null && Objects.equals(existing.id(), entry.id())) {
                resolved.set(i, merge(existing, entry, now));
                return resolved;
            }
        }
        resolved.add(entry);
        return resolved;
    }
}