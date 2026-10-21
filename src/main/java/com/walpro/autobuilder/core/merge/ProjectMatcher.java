package com.walpro.autobuilder.core.merge;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.analysis.structure.JavaSources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * T4-EC stage 3: what in two projects denotes the same thing -- read from their structure first.
 *
 * <p>The user's choice (2026-10-21, option B of three): structure answers what it can, a model only the pairs structure
 * leaves open, and every verdict keeps who gave it ([[project-invented-facts-have-authorities]]). This class is the
 * structural half and never calls a model; {@link ProjectMatchService} asks the model about the {@link Level#CANDIDATE}
 * pairs.
 *
 * <p>Three kinds of thing are compared: the projects' top-level TYPES, the OPERATIONS two different types both offer
 * under one name, and the CRITERIA their goal trees carry. Types are compared by their public surface with each type's
 * own name read as "self" -- {@code Money.plus(Money)} and {@code Amount.plus(Amount)} are one shape.
 */
public final class ProjectMatcher {

    /** How far structure took a pair. */
    public enum Level {
        /** Same name, same kind, same public surface: structure says they are the same thing. */
        EXACT,
        /** Same name, different surface: one name for two shapes -- structure cannot say whether that is one thing. */
        SAME_NAME_DIFFERENT_SHAPE,
        /** Different names, surfaces that largely overlap: perhaps one thing under two names. */
        CANDIDATE
    }

    /** What a pair is: types, an operation two types share by name, or criteria. */
    public enum Kind { TYPE, OPERATION, CRITERION }

    /**
     * One pair, A's side and B's, how far structure took it, and why.
     *
     * @param overlap the share of the two surfaces (or criteria words) they have in common, 0..1
     */
    public record Match(Kind kind, String a, String b, Level level, double overlap, String why) {
    }

    /** A type's comparable surface. */
    record Surface(String name, String kind, Set<String> members, List<String> components, Map<String, String> returns) {
    }

    /** Below this share of common members two differently named types are not put to anyone. */
    static final double CANDIDATE_OVERLAP = 0.5;

    private ProjectMatcher() {
    }

    /**
     * @param mainA A's sources under src/main, path to text; {@code mainB} B's
     * @param criteriaA A's criteria (distinct texts); {@code criteriaB} B's
     */
    public static List<Match> match(Map<String, String> mainA, Map<String, String> mainB,
                                    List<String> criteriaA, List<String> criteriaB) {
        List<Match> out = new ArrayList<>();
        Map<String, Surface> a = surfaces(mainA), b = surfaces(mainB);
        for (Surface sa : a.values()) {
            for (Surface sb : b.values()) {
                double overlap = jaccard(sa.members(), sb.members());
                if (sa.name().equals(sb.name())) {
                    boolean same = sa.kind().equals(sb.kind()) && sa.members().equals(sb.members())
                            && sa.components().equals(sb.components());
                    out.add(new Match(Kind.TYPE, sa.name(), sb.name(), same ? Level.EXACT : Level.SAME_NAME_DIFFERENT_SHAPE,
                            overlap, same ? "same name, kind, public surface and components"
                            : "same name; surfaces differ: only in " + sa.name() + " " + minus(sa.members(), sb.members())
                            + ", only in the other " + minus(sb.members(), sa.members())));
                } else if (overlap >= CANDIDATE_OVERLAP) {
                    out.add(new Match(Kind.TYPE, sa.name(), sb.name(), Level.CANDIDATE, overlap,
                            "different names; common surface " + new TreeSet<>(intersect(sa.members(), sb.members()))));
                }
            }
        }
        // Operations two DIFFERENT types offer under one name: one name may be one operation, or two things. Owners
        // already paired as types are left out -- their shared operations are that pair's surface.
        Set<String> pairedTypes = new LinkedHashSet<>();
        for (Match m : out) pairedTypes.add(m.a() + "\u0000" + m.b());
        for (Surface sa : a.values()) {
            for (Surface sb : b.values()) {
                if (sa.name().equals(sb.name()) || pairedTypes.contains(sa.name() + "\u0000" + sb.name())) continue;
                for (Map.Entry<String, String> op : sa.returns().entrySet()) {
                    String other = sb.returns().get(op.getKey());
                    if (other == null) continue;
                    out.add(new Match(Kind.OPERATION, sa.name() + "." + op.getKey(), sb.name() + "." + op.getKey(),
                            Level.CANDIDATE, op.getValue().equals(other) ? 1.0 : 0.5,
                            "one operation name on two types; returns " + op.getValue() + " and " + other));
                }
            }
        }
        // Criteria: the same text, or texts sharing most of their words.
        Set<String> seen = new LinkedHashSet<>();
        for (String ca : criteriaA) {
            for (String cb : criteriaB) {
                if (!seen.add(ca + "\u0000" + cb)) continue;
                if (normalise(ca).equals(normalise(cb))) {
                    out.add(new Match(Kind.CRITERION, ca, cb, Level.EXACT, 1.0, "the same text"));
                    continue;
                }
                double overlap = jaccard(words(ca), words(cb));
                if (overlap >= CANDIDATE_OVERLAP) {
                    out.add(new Match(Kind.CRITERION, ca, cb, Level.CANDIDATE, overlap, "most words in common"));
                }
            }
        }
        return out;
    }

    /** Every public top-level type of {@code sources}, by simple name. */
    static Map<String, Surface> surfaces(Map<String, String> sources) {
        Map<String, Surface> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (!e.getKey().endsWith(".java")) continue;
            CompilationUnit cu;
            try {
                var parsed = JavaSources.parser().parse(e.getValue());
                if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) continue;
                cu = parsed.getResult().get();
            } catch (Exception ex) {
                continue;
            }
            for (TypeDeclaration<?> t : cu.getTypes()) {
                String self = t.getNameAsString();
                String kind = t instanceof RecordDeclaration ? "record" : t instanceof EnumDeclaration ? "enum"
                        : t instanceof ClassOrInterfaceDeclaration c && c.isInterface() ? "interface" : "class";
                Set<String> members = new TreeSet<>();
                Map<String, String> returns = new LinkedHashMap<>();
                List<String> components = new ArrayList<>();
                if (t instanceof RecordDeclaration r) {
                    r.getParameters().forEach(p -> components.add(selfed(p.getType().asString(), self) + " " + p.getNameAsString()));
                    // A record's components are accessors of its surface too.
                    r.getParameters().forEach(p -> members.add(p.getNameAsString() + "()"));
                }
                if (t instanceof EnumDeclaration en) en.getEntries().forEach(c -> members.add("constant " + c.getNameAsString()));
                for (BodyDeclaration<?> m : t.getMembers()) {
                    if (!(m instanceof CallableDeclaration<?> cd)) continue;
                    boolean visible = cd.isPublic() || (t instanceof ClassOrInterfaceDeclaration c && c.isInterface() && !cd.isPrivate());
                    if (!visible) continue;
                    String params = cd.getParameters().stream().map(p -> selfed(p.getType().asString(), self))
                            .reduce((x, y) -> x + "," + y).orElse("");
                    String name = cd.isConstructorDeclaration() ? "<init>" : cd.getNameAsString();
                    members.add((cd.isStatic() ? "static " : "") + name + "(" + params + ")");
                    if (cd instanceof MethodDeclaration md && !md.isStatic()) {
                        returns.put(name + "(" + params + ")", md.getType().asString());
                    }
                }
                out.putIfAbsent(self, new Surface(self, kind, members, components, returns));
            }
        }
        return out;
    }

    private static String selfed(String type, String self) {
        return type.equals(self) ? "SELF" : type;
    }

    private static double jaccard(Set<String> x, Set<String> y) {
        if (x.isEmpty() && y.isEmpty()) return 0;
        return (double) intersect(x, y).size() / (x.size() + y.size() - intersect(x, y).size());
    }

    private static Set<String> intersect(Set<String> x, Set<String> y) {
        Set<String> i = new TreeSet<>(x);
        i.retainAll(y);
        return i;
    }

    private static Set<String> minus(Set<String> x, Set<String> y) {
        Set<String> d = new TreeSet<>(x);
        d.removeAll(y);
        return d;
    }

    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[`'\".,;:]", "").replaceAll("\\s+", " ").strip();
    }

    private static Set<String> words(String s) {
        Set<String> out = new TreeSet<>();
        for (String w : normalise(s).split("[^a-z0-9]+")) if (w.length() > 2) out.add(w);
        return out;
    }
}
