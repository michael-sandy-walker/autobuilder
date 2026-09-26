package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-CQ: notices when the loop repeats the same failing move, and says so -- to the operator and to
 * the model.
 *
 * <p>The second adopted project spent twenty-five minutes on one move: a test called
 * {@code getChildren()} on a record that declares {@code children()}, the compile failed, the model
 * rewrote the protected record, the write was refused, and the next cycle did it again. The loop
 * saw it -- the repetition signal fired 72 times -- and told nobody: the signal only re-ranks agents,
 * and the recurring-defect tracker counts missing TYPES under two or more subgoals, by design. The
 * user: "Whenever there is a grind, which seems to be repeating itself (seemingly forever), it
 * should be noted."
 *
 * <p>A failure is reduced to a SIGNATURE -- what failed, in the build's own terms, without file
 * positions -- and each signature is counted at most once per cycle, so one cycle that meets the
 * same refusal at the merge and at the probe is one occurrence. A signature is noted at its third
 * occurrence and again each time its count doubles: a grind that goes on forever gets louder, not
 * quieter. Three is the smallest count that is a repetition of a repetition; the doubling keeps a
 * long grind from flooding the log while never letting it go silent.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "per-run failure-signature counts; a restored run starts counting afresh")
public class GrindDetector {

    static final int FIRST_NOTICE = 3;

    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    private final Map<String, Entry> bySignature = new LinkedHashMap<>();
    private String currentCycle;
    private final Set<String> seenThisCycle = new HashSet<>();
    private String lastCycleWithFailure;

    private static final class Entry {
        int occurrences;
        String firstCycle;
        String lastCycle;
        int nextNotice = FIRST_NOTICE;
    }

    /** Marks the start of a cycle; occurrences are counted at most once per cycle. */
    public synchronized void beginCycle(String cycleId) {
        if (cycleId != null && !cycleId.equals(currentCycle)) {
            currentCycle = cycleId;
            seenThisCycle.clear();
        }
    }

    /** Records one failure. {@code cycleId} may be null, then the current cycle is assumed. */
    public synchronized void observe(String cycleId, String signature) {
        if (signature == null || signature.isBlank()) return;
        if (cycleId != null) beginCycle(cycleId);
        String cycle = currentCycle;
        if (!seenThisCycle.add(signature)) return;
        lastCycleWithFailure = cycle;
        Entry e = bySignature.computeIfAbsent(signature, k -> new Entry());
        e.occurrences++;
        if (e.firstCycle == null) e.firstCycle = cycle;
        e.lastCycle = cycle;
        if (e.occurrences >= e.nextNotice) {
            e.nextNotice = e.occurrences * 2;
            log.warn("T4-CQ: GRIND -- the same failure has now happened in {} cycles (since {}): {}",
                    e.occurrences, e.firstCycle, signature);
            if (behaviourLogger != null) {
                behaviourLogger.grindDetected(cycle, signature, e.occurrences, e.firstCycle);
            }
        }
    }

    /**
     * Records every signature of one failed build outcome. A build that succeeds ends whatever was
     * grinding: without that, the prompt kept naming a grind for as long as no later build failed
     * (structdiff run 2 still warned about a getter error forty minutes after the last one).
     */
    public void observeBuild(String cycleId, BuildOutcome outcome) {
        if (outcome != null && outcome.result() == BuildResult.COMPILE_OK) {
            synchronized (this) {
                lastCycleWithFailure = null;
            }
            return;
        }
        for (String s : signaturesOf(outcome)) observe(cycleId, s);
    }

    /**
     * What a failed build failed ON, in the compiler's own terms and without positions: a member
     * missing from a named type where the diagnostic says so, otherwise the diagnostic's message.
     * Several distinct ones per build, since one move often breaks several things at once.
     */
    static List<String> signaturesOf(BuildOutcome outcome) {
        List<String> out = new ArrayList<>();
        if (outcome == null || outcome.result() != BuildResult.COMPILE_ERROR || outcome.stderrTail() == null) {
            return out;
        }
        for (MissingMemberExtractor.Entry e : MissingMemberExtractor.extract(outcome.stderrTail())) {
            String member = e.kind() == MissingMemberExtractor.Kind.CONSTRUCTOR
                    ? "constructor " + e.targetType() + "(" + e.argTypes() + ")"
                    : e.memberName() + "(" + e.argTypes() + ")";
            String s = "the build fails: " + e.targetType() + " has no " + member;
            if (!out.contains(s)) out.add(s);
        }
        if (!out.isEmpty()) return out;
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(outcome.stderrTail()).replaceAll("");
        String[] lines = plain.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            // "[ERROR] /abs/path/File.java:[12,5] message" -> "File.java: message"
            var m = java.util.regex.Pattern.compile("([A-Za-z0-9_]+\\.java):\\[\\d+,\\d+]\\s*(.+)$").matcher(lines[i]);
            if (!m.find()) continue;
            String message = m.group(2).strip();
            if (message.equals("cannot find symbol")) message += symbolAndLocation(lines, i + 1);
            String s = "the build fails: " + m.group(1) + ": " + message;
            if (!out.contains(s)) out.add(s);
            if (out.size() >= 5) break;
        }
        return out;
    }

    /**
     * The {@code symbol:} and {@code location:} lines javac prints under "cannot find symbol", as
     * " variable subGoalId in class ChannelRequest", packages dropped. Without them the signature named
     * only the file, and the question it poses ("where is this name declared?") could not be asked:
     * three of the seven grinds that outlived every reaction in the archive were such signatures
     * (T4-DY slice A, 2026-09-26). Empty when the lines are not there.
     */
    static String symbolAndLocation(String[] lines, int from) {
        String symbol = null, location = null;
        for (int j = from; j < Math.min(lines.length, from + 3); j++) {
            var sm = java.util.regex.Pattern.compile("symbol:\\s*(.+)$").matcher(lines[j]);
            var lm = java.util.regex.Pattern.compile("location:\\s*(.+)$").matcher(lines[j]);
            if (symbol == null && sm.find()) symbol = sm.group(1).strip();
            else if (location == null && lm.find()) location = lm.group(1).strip();
        }
        if (symbol == null) return "";
        String out = " " + symbol + (location == null ? "" : " in " + location);
        return out.replaceAll("\\b(?:[a-z_][\\w]*\\.)+([A-Z][\\w$]*)", "$1");
    }

    /**
     * The evolution prompt's block: every signature that has reached its first notice and failed
     * again in the most recent failing cycle. Null when nothing is grinding.
     */
    public synchronized String promptBlock() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Entry> e : bySignature.entrySet()) {
            Entry v = e.getValue();
            if (v.occurrences >= FIRST_NOTICE && v.lastCycle != null && v.lastCycle.equals(lastCycleWithFailure)) {
                lines.add("  - " + e.getKey() + " -- " + v.occurrences + " times so far");
            }
        }
        if (lines.isEmpty()) return null;
        return "--- THIS HAS FAILED REPEATEDLY ---\n"
                + "The loop has made the same failing move again and again. Doing it once more will fail\n"
                + "the same way. Change the approach, not the wording:\n"
                + String.join("\n", lines);
    }

    /** Signatures and their occurrence counts, for tests and the run report. */
    public synchronized Map<String, Integer> occurrences() {
        Map<String, Integer> out = new LinkedHashMap<>();
        bySignature.forEach((k, v) -> out.put(k, v.occurrences));
        return out;
    }
}
