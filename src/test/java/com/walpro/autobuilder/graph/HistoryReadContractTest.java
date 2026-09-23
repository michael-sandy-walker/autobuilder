package com.walpro.autobuilder.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-ER: every graph read declares how much history it spans and what its answer may do, and no
 * read lets another lineage's measurement decide this lineage's work unless it says why that is
 * safe.
 *
 * <p>Reads are found in the SOURCE, not by reflection, because what makes a method a read is the
 * query text inside it. A read is any Cypher literal containing {@code MATCH} and none of the
 * write clauses; its method is the nearest enclosing class member.
 *
 * <p>The declared span is also checked against the query where the query can speak for it: a
 * method declared {@link HistorySpan#RUN} must filter on {@code runId} somewhere, one declared
 * {@link HistorySpan#LINEAGE} on {@code lineageId}. So the declaration cannot drift from the
 * query it describes without the build noticing.
 */
class HistoryReadContractTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern WRITE = Pattern.compile("\\b(SET|MERGE|DELETE|CREATE|REMOVE)\\b");
    private static final Pattern MATCH = Pattern.compile("\\bMATCH\\b");
    private static final Pattern MEMBER = Pattern.compile(
            "^    (?:public |protected |private |static |final |synchronized |default |abstract )*"
                    + "[\\w<>\\[\\],.? ]+?\\s+(\\w+)\\s*\\(");

    /** One read query and the member it lives in. */
    record Read(Path file, int line, String method, String query, boolean annotated) {
        String where() {
            return MAIN.relativize(file) + ":" + line + " " + method + "()";
        }
    }

    @Test
    @DisplayName("every graph read declares its history span")
    void everyReadIsDeclared() throws IOException {
        List<String> undeclared = reads().stream()
                .filter(r -> !r.annotated())
                .map(Read::where)
                .toList();
        assertThat(undeclared)
                .as("""
                    These methods read the graph and do not say how much history the answer spans. \
                    Add @HistoryRead(span = …, use = …, note = "who consumes it and why this span"). \
                    RUN and LINEAGE are checked against the query; ALL_LINEAGES + BINDING also needs \
                    crossLineageBecause.""")
                .isEmpty();
    }

    @Test
    @DisplayName("another lineage's answer only informs, unless the read says why it may decide")
    void crossLineageReadsAreAdvisoryOrJustified() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Method m : declaredMethods()) {
            HistoryRead h = m.getAnnotation(HistoryRead.class);
            if (h.note().isBlank()) violations.add(name(m) + ": blank note");
            if (h.span() == HistorySpan.ALL_LINEAGES && h.use() == HistoryUse.BINDING
                    && h.crossLineageBecause().isBlank()) {
                violations.add(name(m) + ": binding across all lineages with no crossLineageBecause");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a declared RUN or LINEAGE span is visible in the query itself")
    void declaredSpanMatchesTheQuery() throws Exception {
        Map<String, StringBuilder> queriesByMethod = new LinkedHashMap<>();
        for (Read r : reads()) {
            queriesByMethod.computeIfAbsent(key(r.file(), r.method()), k -> new StringBuilder())
                    .append(r.query()).append('\n');
        }
        List<String> drift = new ArrayList<>();
        for (Method m : declaredMethods()) {
            HistoryRead h = m.getAnnotation(HistoryRead.class);
            StringBuilder q = queriesByMethod.get(key(sourceOf(m.getDeclaringClass()), m.getName()));
            if (q == null) continue;
            if (h.span() == HistorySpan.RUN && !q.toString().contains("runId")) {
                drift.add(name(m) + ": declared RUN, no runId filter in its query");
            }
            if (h.span() == HistorySpan.LINEAGE && !q.toString().contains("lineageId")) {
                drift.add(name(m) + ": declared LINEAGE, no lineageId filter in its query");
            }
        }
        assertThat(drift).isEmpty();
    }

    @Test
    @DisplayName("the scan finds reads at all — a scanner that finds nothing passes everything")
    void theScannerSeesTheKnownReads() throws IOException {
        List<String> methods = reads().stream().map(Read::method).toList();
        assertThat(methods).contains("findAll", "findDescriptionsByStatus", "findCriterionResults");
        assertThat(methods.size()).isGreaterThan(30);
    }

    // ---------------------------------------------------------------------------------

    static List<Read> reads() throws IOException {
        List<Read> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                out.addAll(readsIn(f));
            }
        }
        return out;
    }

    private static List<Read> readsIn(Path file) throws IOException {
        List<String> raw = Files.readAllLines(file);
        // Blank out comment lines that sit OUTSIDE text blocks (a commented-out query is not a
        // read), while keeping Cypher `//` comments that sit inside a query.
        List<String> lines = new ArrayList<>(raw.size());
        boolean inBlock = false;
        for (String l : raw) {
            String t = l.strip();
            boolean comment = !inBlock && (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"));
            lines.add(comment ? "" : l);
            if (!comment && countOf(l, "\"\"\"") % 2 == 1) inBlock = !inBlock;
        }

        List<Read> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            String query = null;
            int start = i;
            if (l.contains("\"\"\"")) {
                // text block: from the line after the opener to the closer
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < lines.size() && !lines.get(j).contains("\"\"\"")) sb.append(lines.get(j++)).append('\n');
                String rest = j < lines.size() ? lines.get(j) : "";
                sb.append(rest, 0, Math.max(0, rest.indexOf("\"\"\"")));
                query = sb.toString();
                i = j;
            } else {
                Matcher m = Pattern.compile("\"([^\"]*\\bMATCH\\b[^\"]*)\"").matcher(l);
                if (m.find()) query = m.group(1);
            }
            if (query == null || !MATCH.matcher(query).find()) continue;
            String code = query.replaceAll("//[^\n]*", "");
            if (WRITE.matcher(code).find()) continue;
            int decl = enclosingMember(lines, start);
            if (decl < 0) continue;
            Matcher mm = MEMBER.matcher(lines.get(decl));
            if (!mm.find()) continue;
            out.add(new Read(file, start + 1, mm.group(1), code, annotated(lines, decl)));
        }
        return out;
    }

    private static int enclosingMember(List<String> lines, int from) {
        for (int i = from; i >= 0; i--) {
            String l = lines.get(i);
            if (!l.startsWith("    ") || l.startsWith("     ")) continue;
            if (l.startsWith("    @") || l.strip().isEmpty()) continue;
            if (MEMBER.matcher(l).find() && !l.strip().startsWith("return")) return i;
        }
        return -1;
    }

    /** Annotations sit between the previous member's end (or its Javadoc) and the declaration. */
    private static boolean annotated(List<String> lines, int decl) {
        for (int i = decl - 1; i >= 0; i--) {
            String l = lines.get(i);
            if (l.contains("@HistoryRead")) return true;
            if (l.equals("    }") || l.strip().endsWith("*/") || l.strip().endsWith(";")
                    && l.startsWith("    ") && !l.startsWith("     ")) {
                return false;
            }
        }
        return false;
    }

    private static List<Method> declaredMethods() throws Exception {
        List<Method> out = new ArrayList<>();
        for (Read r : reads()) {
            Class<?> c = Class.forName(fqn(r.file()));
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(r.method()) && m.isAnnotationPresent(HistoryRead.class)
                        && !out.contains(m)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static String fqn(Path file) {
        String rel = MAIN.relativize(file).toString();
        return rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
    }

    private static Path sourceOf(Class<?> c) {
        while (c.getEnclosingClass() != null) c = c.getEnclosingClass();
        return MAIN.resolve(c.getName().replace('.', '/') + ".java");
    }

    private static String key(Path file, String method) {
        return file.normalize() + "#" + method;
    }

    private static String name(Method m) {
        return m.getDeclaringClass().getSimpleName() + "." + m.getName();
    }

    private static int countOf(String s, String token) {
        int n = 0;
        for (int i = s.indexOf(token); i >= 0; i = s.indexOf(token, i + token.length())) n++;
        return n;
    }
}
