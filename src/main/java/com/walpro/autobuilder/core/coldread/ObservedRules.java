package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Account;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Feature;
import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-EK slice (c): how work on a project the loop did not build is to be done, proposed from what reverse engineering
 * OBSERVED of it -- for the operator to confirm, change or delete, like the seed.
 *
 * <p>The seed states WHAT the project does, and every line of it is verified. These lines say HOW a change is made so
 * that a first new feature fits the project it joins; nothing verifies them, so each is proposed only where it rests
 * on an observation, and says which:
 * <ul>
 *   <li>one feature's checks stopped holding when ANOTHER feature's ways in were taken out -- it goes through that
 *       feature, and a change there must keep it working;</li>
 *   <li>features' checks stopped holding when a part of the code was taken out -- what they do rests on that part,
 *       and that kind of behaviour is changed there, not built a second time beside it.</li>
 * </ul>
 * No model writes them: each is a sentence around names the account already holds. A project where neither was
 * observed gets none.
 */
public final class ObservedRules {

    /** What a rule line starts with, in the seed editor and in the obligations file. */
    public static final String MARKER = "rule:";
    /** The line above the proposed rules in the composed text; read back as a note, never as a seed line. */
    public static final String HEADING = "How work on it is done";
    static final String HEADING_LINE = HEADING + " (these guide the loop's work and are not part of the seed; nothing "
            + "checks them -- change or delete them as you see fit):";
    static final int MAX_TYPES_NAMED = 6;

    private ObservedRules() {
    }

    /** The proposed rules, each one sentence; empty when nothing observed supports one. */
    public static List<String> propose(Account account) {
        List<String> out = new ArrayList<>();
        for (Feature f : SeedComposer.ordered(account.features())) {
            if (f.dependsOn().isEmpty() || f.claims().isEmpty()) continue;
            out.add(f.name() + " goes through " + list(f.dependsOn()) + " (its checks stop holding when "
                    + (f.dependsOn().size() == 1 ? "that is" : "one of those is") + " taken out): a change to "
                    + list(f.dependsOn(), "or") + " keeps what " + f.name() + " does working.");
        }
        Map<String, Set<String>> restingOn = new LinkedHashMap<>();   // capability id -> the features made of it
        for (Feature f : SeedComposer.ordered(account.features())) {
            if (f.claims().isEmpty()) continue;
            for (String c : f.madeOf()) restingOn.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(f.name());
        }
        for (Capability c : account.capabilities()) {
            Set<String> features = restingOn.get(c.id());
            if (features == null) continue;
            Set<String> types = new LinkedHashSet<>();
            for (String d : c.declarations()) types.add(d.contains(".") ? d.substring(0, d.indexOf('.')) : d);
            if (types.isEmpty()) continue;
            List<String> named = types.stream().limit(MAX_TYPES_NAMED).toList();
            out.add("\"" + c.name() + "\" is the work of " + list(named)
                    + (types.size() > named.size() ? " and " + (types.size() - named.size()) + " more" : "")
                    + ", and what " + list(List.copyOf(features)) + (features.size() == 1 ? " does" : " do")
                    + " rests on it (seen by taking it out): behaviour of that kind is changed there, not built a "
                    + "second time beside it.");
        }
        return out;
    }

    /** The proposed rules as the block that follows a composed seed; empty when there are none. */
    public static String block(Account account) {
        List<String> rules = propose(account);
        if (rules.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n").append(HEADING_LINE).append('\n');
        rules.forEach(r -> sb.append(MARKER).append(' ').append(r).append('\n'));
        return sb.toString();
    }

    private static String list(List<String> names) {
        return list(names, "and");
    }

    private static String list(List<String> names, String last) {
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " " + last + " " + names.get(names.size() - 1);
    }
}
