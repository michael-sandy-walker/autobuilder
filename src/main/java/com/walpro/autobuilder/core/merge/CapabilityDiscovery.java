package com.walpro.autobuilder.core.merge;

import com.walpro.autobuilder.core.investigate.LogInvestigator;
import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;
import com.walpro.autobuilder.core.merge.CapabilityCut.Declaration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EI: what another project can do, found by a model probing its files step by step (T4-EE's tree probes) -- with
 * structure first: the source's declarations are listed for it, and a capability cites them by the ids listed, so what
 * it claims can be checked. A capability is not a fixed unit: its declarations are the evidence a cut is made by;
 * what it does, what it needs, and whether it is complex enough to keep its source readable are the model's to say.
 */
@Slf4j
@Component
public class CapabilityDiscovery {

    static final int PROBES = 10;

    private static final Pattern BLOCK = Pattern.compile("(?mi)^\\s*CAPABILITY:\\s*(.+?)\\s*$");
    private static final Pattern FIELD = Pattern.compile("(?mi)^\\s*(DECLARATIONS|DEPENDENCIES|REFERENCE):\\s*(.*?)\\s*$");

    private final LogInvestigator investigator;

    public CapabilityDiscovery(LogInvestigator investigator) {
        this.investigator = investigator;
    }

    /**
     * @param capabilities what was found, ids {@code c1, c2, ...} in the order given
     * @param unknown      ids a capability cited that the source does not declare -- dropped, and shown
     * @param probes       every probe the model made, so a reader can check the answer against what it saw
     */
    public record Found(List<Capability> capabilities, List<String> unknown, List<String> probes, String answer) {
    }

    /**
     * @param code   the source's code, read-only, for the probes
     * @param source its files by path, for the declarations
     * @param goals  what the source was built toward, one goal a line, or null when it has no goal tree
     */
    public Found discover(Path code, Map<String, String> source, String goals) {
        Map<String, Declaration> inventory = CapabilityCut.inventory(source);
        LogInvestigator.Investigation inv = investigator.investigate(question(inventory, goals), Map.of(), code, PROBES,
                "capability-discovery");
        List<String> probes = inv.steps().stream().map(LogInvestigator.Step::probe).toList();
        if (!inv.answered()) {
            log.warn("T4-EI: capability discovery got no answer after {} probe(s)", probes.size());
            return new Found(List.of(), List.of(), probes, null);
        }
        Found found = parse(inv.answer(), inventory, probes);
        log.warn("T4-EI: capability discovery -- {} capabilities, {} unknown declaration(s), {} probe(s)",
                found.capabilities().size(), found.unknown().size(), probes.size());
        return found;
    }

    static String question(Map<String, Declaration> inventory, String goals) {
        StringBuilder sb = new StringBuilder();
        sb.append("Which capabilities does this project have that another project could take in? A capability is a")
                .append(" piece of what it can do: a mechanism, a part of a framework, a function, a principle it follows.")
                .append(" It is as large as it needs to be -- a few declarations, or many, with the libraries it relies on.")
                .append(" Name each capability once; a declaration may belong to more than one when several need it.\n");
        if (goals != null && !goals.isBlank()) {
            sb.append("\nWHAT THE PROJECT WAS BUILT TO DO (its goals, as its own run recorded them):\n").append(goals.strip()).append('\n');
        }
        sb.append("\nITS DECLARATIONS, by file -- an id, then what it declares:\n");
        String file = null;
        for (Declaration d : inventory.values()) {
            if (!d.path().equals(file)) {
                file = d.path();
                sb.append(file).append('\n');
            }
            sb.append("  ").append(d.id()).append("  --  ").append(d.signature()).append('\n');
        }
        sb.append("""

                Probe the files to see what the declarations do before you answer. Then answer with one block per \
                capability, in this form:

                CAPABILITY: <a short name> | <what it does, in one sentence>
                DECLARATIONS: <the ids above that make it up, comma-separated: a type's id takes the whole type; a \
                member's id (Type.member) takes only that member -- name members when only some of a type belongs to it, \
                and name the types it needs in order to work>
                DEPENDENCIES: <libraries or frameworks it needs beyond the language itself, or none>
                REFERENCE: <yes or no> | <whether someone re-implementing it elsewhere would need to read its source: \
                yes when it is complex enough that a description would lose what matters>
                """);
        return sb.toString();
    }

    /**
     * The cut, worked out by a model probing the source once the operator has chosen: which declarations make the code
     * taken work on its own ({@code take}), and which members of what is taken serve only a capability not taken
     * ({@code leave}, each with that capability's name). Discovery's attribution says what a capability IS; this says
     * what has to travel for the ones taken -- the types they need, even where another capability lists them too.
     * {@code answered} is false when the model gave no usable answer; nothing is guessed then.
     */
    public record Cut(List<String> take, Map<String, String> leave, List<String> unknown, List<String> probes, boolean answered) {
    }

    private static final Pattern TAKE = Pattern.compile("(?mi)^\\s*TAKE:\\s*(.*?)\\s*$");
    private static final Pattern LEAVE = Pattern.compile("(?mi)^\\s*LEAVE:\\s*(.*?)\\s*$");
    private static final Pattern LEAVE_ITEM = Pattern.compile("([A-Za-z0-9_.$]+)\\s*(?:\\(([^)]*)\\))?");

    public Cut cut(Path code, Map<String, String> source, List<Capability> taken, List<Capability> notTaken) {
        Map<String, Declaration> inventory = CapabilityCut.inventory(source);
        LogInvestigator.Investigation inv = investigator.investigate(cutQuestion(inventory, taken, notTaken), Map.of(), code,
                PROBES, "capability-cut");
        List<String> probes = inv.steps().stream().map(LogInvestigator.Step::probe).toList();
        if (!inv.answered()) return new Cut(List.of(), Map.of(), List.of(), probes, false);
        Cut cut = parseCut(inv.answer(), inventory, probes);
        log.warn("T4-EI: capability cut -- {} declaration(s) taken, {} member(s) left out, {} unknown, {} probe(s)",
                cut.take().size(), cut.leave().size(), cut.unknown().size(), probes.size());
        return cut;
    }

    static String cutQuestion(Map<String, Declaration> inventory, List<Capability> taken, List<Capability> notTaken) {
        StringBuilder sb = new StringBuilder("Some capabilities of this project are to be taken into another project as code;")
                .append(" the others are not.\n\nTAKEN AS CODE:\n");
        taken.forEach(c -> sb.append("- ").append(c.name()).append(": ").append(c.summary()).append(" (listed: ")
                .append(String.join(", ", c.declarations())).append(")\n"));
        sb.append("\nNOT TAKEN:\n");
        notTaken.forEach(c -> sb.append("- ").append(c.name()).append(": ").append(c.summary()).append(" (listed: ")
                .append(String.join(", ", c.declarations())).append(")\n"));
        sb.append("\nTHE PROJECT'S DECLARATIONS, by file -- an id, then what it declares:\n");
        String file = null;
        for (Declaration d : inventory.values()) {
            if (!d.path().equals(file)) {
                file = d.path();
                sb.append(file).append('\n');
            }
            sb.append("  ").append(d.id()).append("  --  ").append(d.signature()).append('\n');
        }
        sb.append("""

                Decide exactly which declarations travel, so that the code taken compiles and works on its own and \
                carries nothing of what is not taken. Probe the files to see what each declaration uses. Bring the types \
                and members the taken code needs -- including types a capability not taken also lists, when the taken \
                code needs them. Where a type travels but some of its members serve only a capability not taken, name \
                those members to leave out. Answer in this form:

                TAKE: <ids from the list above, comma-separated; a type's id brings the whole type except the members \
                named under LEAVE>
                LEAVE: <member ids (Type.member) of types that travel, each followed by the capability not taken it \
                serves in parentheses, comma-separated; or none>
                """);
        return sb.toString();
    }

    static Cut parseCut(String answer, Map<String, Declaration> inventory, List<String> probes) {
        List<String> take = new ArrayList<>(), unknown = new ArrayList<>();
        Map<String, String> leave = new LinkedHashMap<>();
        Matcher t = TAKE.matcher(answer);
        if (t.find()) {
            for (String id : split(t.group(1))) {
                String clean = id.replaceAll("^[`'\"]+|[`'\"]+$", "");
                if (inventory.containsKey(clean)) {
                    if (!take.contains(clean)) take.add(clean);
                } else if (!clean.isEmpty()) {
                    unknown.add(clean);
                }
            }
        }
        Matcher l = LEAVE.matcher(answer);
        if (l.find() && !l.group(1).strip().equalsIgnoreCase("none")) {
            Matcher item = LEAVE_ITEM.matcher(l.group(1));
            while (item.find()) {
                String id = item.group(1);
                if (id.equalsIgnoreCase("none")) continue;
                if (inventory.containsKey(id) && !inventory.get(id).kind().equals("type")) {
                    leave.put(id, item.group(2) == null ? "" : item.group(2).strip());
                } else {
                    unknown.add(id);
                }
            }
        }
        return new Cut(take, leave, unknown, probes, !take.isEmpty());
    }

    static Found parse(String answer, Map<String, Declaration> inventory, List<String> probes) {
        List<Capability> out = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Matcher m = BLOCK.matcher(answer);
        List<int[]> starts = new ArrayList<>();
        while (m.find()) starts.add(new int[]{m.start(), m.end()});
        for (int i = 0; i < starts.size(); i++) {
            String head = answer.substring(starts.get(i)[0], starts.get(i)[1]).replaceFirst("(?i)^\\s*CAPABILITY:\\s*", "");
            String body = answer.substring(starts.get(i)[1], i + 1 < starts.size() ? starts.get(i + 1)[0] : answer.length());
            String[] nameAndWhat = head.split("\\s*\\|\\s*", 2);
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher f = FIELD.matcher(body);
            while (f.find()) fields.putIfAbsent(f.group(1).toUpperCase(java.util.Locale.ROOT), f.group(2));
            List<String> declarations = new ArrayList<>();
            for (String id : split(fields.getOrDefault("DECLARATIONS", ""))) {
                String clean = id.replaceAll("^[`'\"]+|[`'\"]+$", "");
                if (inventory.containsKey(clean)) {
                    if (!declarations.contains(clean)) declarations.add(clean);
                } else if (!clean.isEmpty()) {
                    unknown.add(clean);
                }
            }
            List<String> dependencies = split(fields.getOrDefault("DEPENDENCIES", "")).stream()
                    .filter(d -> !d.equalsIgnoreCase("none")).toList();
            String[] reference = fields.getOrDefault("REFERENCE", "no").split("\\s*\\|\\s*", 2);
            boolean keep = reference[0].strip().toLowerCase(java.util.Locale.ROOT).startsWith("yes");
            out.add(new Capability("c" + (out.size() + 1), nameAndWhat[0].strip(),
                    nameAndWhat.length > 1 ? nameAndWhat[1].strip() : "", List.copyOf(declarations), dependencies, keep,
                    reference.length > 1 ? reference[1].strip() : null));
        }
        return new Found(out, unknown, probes, answer);
    }

    private static List<String> split(String s) {
        return Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
    }
}
