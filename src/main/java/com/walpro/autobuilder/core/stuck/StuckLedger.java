package com.walpro.autobuilder.core.stuck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-DY slice B: the one home every stuck detector reports to, and its memory of what was asked.
 *
 * <p>Keyed by signature. For each: how often it happened, which question is next, every question
 * asked with the authority that answered and the cycle the answer was delivered in, and whether the
 * signature came back AFTER a delivery. A recurrence after a delivered answer means that answer was
 * not the missing fact, so the entry moves to the NEXT question -- never the same move again. With no
 * question left, the entry asks the operator (a diagnosis request) and stays there.
 *
 * <p>Deterministic and Spring-free; {@link StuckLayer} wires it to the loop.
 */
public final class StuckLedger {

    /** Where an entry stands. */
    public enum Status {
        /** Seen, but not yet often enough to be a repetition of a repetition. */
        WATCHING,
        /** A question is due at the next evolution prompt. */
        PENDING,
        /** An answer was delivered and the signature has not come back since. */
        DELIVERED,
        /** A build succeeded after the delivery with the signature not recurring: ended by the delivered fact. */
        ENDED,
        /** No question left that an authority here could answer: handed to the operator. */
        UNANSWERED
    }

    /** One question asked, as recorded. */
    public record Question(int rung, String question, String authority, String answer, String cycleAsked,
                           String deliveredToSubGoal) {
    }

    /** What the ledger holds for one signature. */
    public static final class Entry {
        final StuckObservation.Source source;
        final String signature;
        List<String> detail = List.of();
        String subGoalId;
        int occurrences;
        String firstCycle;
        String lastCycle;
        int rung;
        Status status = Status.WATCHING;
        final List<Question> asked = new ArrayList<>();
        int recurrencesAfterDelivery;
        String deliveredCycle;
        String endedCycle;
        boolean diagnosisRequested;
        /** Observation sequence at the last count and at the last delivery, so a recurrence in the delivery's own cycle is seen. */
        long lastObservedSeq;
        long deliveredSeq;

        Entry(StuckObservation.Source source, String signature) {
            this.source = source;
            this.signature = signature;
        }

        public StuckObservation.Source source() { return source; }
        public String signature() { return signature; }
        public List<String> detail() { return detail; }
        public String subGoalId() { return subGoalId; }
        public int occurrences() { return occurrences; }
        public String firstCycle() { return firstCycle; }
        public String lastCycle() { return lastCycle; }
        public int rung() { return rung; }
        public Status status() { return status; }
        public List<Question> asked() { return List.copyOf(asked); }
        public int recurrencesAfterDelivery() { return recurrencesAfterDelivery; }
        public String deliveredCycle() { return deliveredCycle; }
        public String endedCycle() { return endedCycle; }
        public boolean diagnosisRequested() { return diagnosisRequested; }

        /** The last answer delivered, or null. */
        public String deliveredAnswer() {
            for (int i = asked.size() - 1; i >= 0; i--) {
                if (asked.get(i).answer() != null) return asked.get(i).answer();
            }
            return null;
        }
    }

    /** What one observation changed, for the caller to report. */
    public enum Change {
        NONE, COUNTED, NOW_PENDING, RECURRED_AFTER_DELIVERY
    }

    /**
     * The count at which an observation becomes a question. Three for the repeated ones -- the
     * smallest count that is a repetition of a repetition, as GrindDetector always used; one for the
     * brakes, which only report once their own window is already spent.
     */
    public static int noticeAt(StuckObservation.Source source) {
        return switch (source) {
            case BUILD, PROTECTED_WRITE, CLOSURE_REFUSAL -> 3;
            // TEST_FAILURES arrives only once the test gate has judged it persistent, by ratios over the run.
            case FLAT_ROOT, EXHAUSTION, TEST_FAILURES -> 1;
        };
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long seq;

    private static String key(StuckObservation.Source source, String signature) {
        return source + "|" + signature;
    }

    /** Records one observation; counted at most once per cycle unless an answer was delivered in between. */
    public synchronized Change observe(StuckObservation o) {
        if (o == null || o.source() == null || o.signature() == null || o.signature().isBlank()) return Change.NONE;
        Entry e = entries.computeIfAbsent(key(o.source(), o.signature()), k -> new Entry(o.source(), o.signature()));
        if (!o.detail().isEmpty()) e.detail = o.detail();
        if (o.subGoalId() != null) e.subGoalId = o.subGoalId();
        boolean sameCycle = o.cycleId() != null && o.cycleId().equals(e.lastCycle);
        if (sameCycle && e.deliveredSeq <= e.lastObservedSeq) return Change.NONE;
        e.occurrences++;
        e.lastObservedSeq = ++seq;
        if (e.firstCycle == null) e.firstCycle = o.cycleId();
        e.lastCycle = o.cycleId();
        switch (e.status) {
            case WATCHING -> {
                if (e.occurrences >= noticeAt(e.source)) {
                    e.status = Status.PENDING;
                    return Change.NOW_PENDING;
                }
            }
            case DELIVERED, ENDED -> {
                e.recurrencesAfterDelivery++;
                e.rung++;
                e.status = Status.PENDING;
                e.endedCycle = null;
                return Change.RECURRED_AFTER_DELIVERY;
            }
            default -> {
            }
        }
        return Change.COUNTED;
    }

    /** Entries with a question due, oldest first. */
    public synchronized List<Entry> pending() {
        return entries.values().stream().filter(e -> e.status == Status.PENDING).toList();
    }

    /** Entries whose delivered answer still stands, most recently delivered first. */
    public synchronized List<Entry> delivered() {
        List<Entry> out = new ArrayList<>(entries.values().stream().filter(e -> e.status == Status.DELIVERED).toList());
        out.sort((a, b) -> Long.compare(b.deliveredSeq, a.deliveredSeq));
        return out;
    }

    /**
     * Records the answer to an entry's current question. Answered: delivered. Not answered: the next
     * question becomes current -- and past the last one the entry is UNANSWERED. Returns true when the
     * operator must now be asked (no question left, or the answer itself needs the operator).
     */
    public synchronized boolean recordAsked(Entry e, StuckDiagnosis.Asked asked, String cycleId, String subGoalId) {
        e.asked.add(new Question(e.rung, asked.question(), asked.authority(), asked.answered() ? asked.answer() : null,
                cycleId, asked.answered() ? subGoalId : null));
        if (asked.answered()) {
            e.status = Status.DELIVERED;
            e.deliveredCycle = cycleId;
            e.deliveredSeq = ++seq;
            if (asked.needsOperator() && !e.diagnosisRequested) {
                e.diagnosisRequested = true;
                return true;
            }
            return false;
        }
        e.rung++;
        return noQuestionLeft(e);
    }

    /** Marks an entry whose source has no question at its rung as handed to the operator; true the first time. */
    public synchronized boolean noQuestionLeft(Entry e) {
        if (e.rung < StuckDiagnosis.questionsFor(e.source)) return false;
        e.status = Status.UNANSWERED;
        if (e.diagnosisRequested) return false;
        e.diagnosisRequested = true;
        return true;
    }

    /**
     * A build succeeded: every delivered build signature has ended -- by the delivered fact, since it
     * did not come back after it -- and a build signature still waiting for its question is no longer
     * failing, so it goes back to watching rather than being answered late. Returns the entries ended.
     */
    public synchronized List<Entry> buildSucceeded(String cycleId) {
        List<Entry> ended = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.source != StuckObservation.Source.BUILD) continue;
            if (e.status == Status.DELIVERED) {
                e.status = Status.ENDED;
                e.endedCycle = cycleId;
                ended.add(e);
            } else if (e.status == Status.PENDING) {
                e.status = Status.WATCHING;
            }
        }
        return ended;
    }

    /** Every entry, in first-seen order. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** The ledger as plain data, for the run's ledger file. */
    public synchronized List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.status == Status.WATCHING && e.asked.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", e.source.name());
            m.put("signature", e.signature);
            m.put("status", e.status.name());
            m.put("occurrences", e.occurrences);
            m.put("firstCycle", e.firstCycle);
            m.put("lastCycle", e.lastCycle);
            m.put("subGoalId", e.subGoalId);
            m.put("recurrencesAfterDelivery", e.recurrencesAfterDelivery);
            m.put("deliveredCycle", e.deliveredCycle);
            m.put("endedCycle", e.endedCycle);
            m.put("diagnosisRequested", e.diagnosisRequested);
            if (!e.detail.isEmpty()) m.put("detail", e.detail);
            List<Map<String, Object>> qs = new ArrayList<>();
            for (Question q : e.asked) {
                Map<String, Object> qm = new LinkedHashMap<>();
                qm.put("rung", q.rung());
                qm.put("question", q.question());
                qm.put("authority", q.authority());
                qm.put("answer", q.answer());
                qm.put("cycleAsked", q.cycleAsked());
                qm.put("deliveredToSubGoal", q.deliveredToSubGoal());
                qs.add(qm);
            }
            m.put("questions", qs);
            out.add(m);
        }
        return out;
    }
}
