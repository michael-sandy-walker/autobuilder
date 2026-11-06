package com.walpro.autobuilder.core.merge;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * T4-EI: a capability import's cut, worked out without touching either project. Pure: the source's files, its
 * capabilities and the operator's choices come in; the files to bring in, and what keeps the choices from being met,
 * come out.
 *
 * <p>A capability is whatever discovery found -- behaviour, principles, goals, dependencies; its DECLARATIONS are only the
 * evidence a cut is made by. The cut keeps what a capability taken as code lists, takes out of those files what a
 * capability not taken as code lists, and refuses rather than guess: kept code that still uses something taken out,
 * or a type of the source that is not brought in, is a problem the operator sees before anything lands. Declarations
 * nobody listed travel with their file, and are named so. Absence checked here is absence of the declarations named --
 * not proof that no behaviour of a capability left out came along in other words.
 */
public final class CapabilityCut {

    private CapabilityCut() {
    }

    /** How a chosen capability comes in. */
    public enum Mode {
        LEAVE_OUT, CODE, ADAPT, IDEA;

        public static Mode of(String s) {
            return switch (s == null ? "" : s.strip().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_')) {
                case "code", "take_as_code" -> CODE;
                case "adapt", "adapted" -> ADAPT;
                case "idea", "idea_only" -> IDEA;
                case "leave_out", "leave", "out", "" -> LEAVE_OUT;
                default -> throw new IllegalArgumentException("a capability is left out, taken as code, adapted or taken as an idea: " + s);
            };
        }
    }

    /** One declaration of the source: a type ({@code Type}) or a member of one ({@code Type.member}; overloads share it). */
    public record Declaration(String id, String path, String kind, String signature) {
    }

    /** What discovery found the source can do; {@code declarations} are ids of {@link #inventory}. */
    public record Capability(String id, String name, String summary, List<String> declarations, List<String> dependencies,
                             boolean reference, String referenceWhy) {
    }

    /** The operator's choice for one capability: how it comes in, what it is for here, and whether its source stays readable. */
    public record Choice(String capability, Mode mode, String description, boolean reference) {
    }

    /**
     * @param files       the source's files to bring in, by path, cut
     * @param provenance  each file brought in, and the capabilities taken as code it serves
     * @param cutOut      declarations taken out of a file that is brought in, each with the capability it belongs to
     * @param shared      declarations brought in although a capability not taken as code lists them too, and why
     * @param unattributed declarations no capability lists, brought in with their file
     * @param problems    why the choices cannot be met as made; nothing is brought in while there is one
     */
    public record Result(Map<String, String> files, Map<String, List<String>> provenance, List<String> cutOut,
                         List<String> shared, List<String> unattributed, List<String> problems) {
    }

    private static final String MAIN = "src/main/java/";

    private static JavaParser parser() {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
    }

    /** The source's main Java declarations, by id; a type whose simple name is not unique is named by its package too. */
    public static Map<String, Declaration> inventory(Map<String, String> source) {
        Map<String, List<String[]>> found = new LinkedHashMap<>();   // simple type name -> [path, qualified name]
        Map<String, CompilationUnit> units = units(source);
        units.forEach((path, cu) -> cu.findAll(TypeDeclaration.class).forEach(t -> found
                .computeIfAbsent(typeName(t, false), k -> new ArrayList<>()).add(new String[]{path, typeName(t, true)})));
        Map<String, Declaration> out = new LinkedHashMap<>();
        units.forEach((path, cu) -> {
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                String simple = typeName(t, false);
                String id = found.get(simple).size() > 1 ? typeName(t, true) : simple;
                out.put(id, new Declaration(id, path, "type", head(t)));
                for (BodyDeclaration<?> m : t.getMembers()) {
                    String name = memberName(m);
                    if (name == null) continue;
                    String mid = id + "." + name;
                    out.putIfAbsent(mid, new Declaration(mid, path, m instanceof FieldDeclaration ? "field" : "method", head(m)));
                }
            }
        });
        return out;
    }

    /** Cuts the source as chosen; capabilities with no choice are left out. */
    public static Result cut(Map<String, String> source, List<Capability> capabilities, List<Choice> choices) {
        Map<String, Declaration> inventory = inventory(source);
        Map<String, Mode> mode = new HashMap<>();
        for (Choice c : choices) mode.put(c.capability(), c.mode());
        Map<String, Capability> byId = new LinkedHashMap<>();
        capabilities.forEach(c -> byId.put(c.id(), c));

        // Which capabilities list each declaration, split by whether they come in as code.
        Map<String, List<String>> asCode = new LinkedHashMap<>(), notAsCode = new LinkedHashMap<>();
        for (Capability c : capabilities) {
            boolean code = mode.getOrDefault(c.id(), Mode.LEAVE_OUT) == Mode.CODE;
            for (String d : c.declarations()) {
                if (!inventory.containsKey(d)) continue;
                (code ? asCode : notAsCode).computeIfAbsent(d, k -> new ArrayList<>()).add(c.name());
            }
        }
        // A declaration is brought in when a capability taken as code lists it, or lists its type -- unless it is a member
        // that a capability not taken as code lists by name: the more precise attribution wins, and is shown.
        Set<String> kept = new LinkedHashSet<>(), out = new LinkedHashSet<>();
        List<String> shared = new ArrayList<>(), cutOut = new ArrayList<>();
        for (Declaration d : inventory.values()) {
            String type = d.kind().equals("type") ? d.id() : d.id().substring(0, d.id().lastIndexOf('.'));
            boolean listed = asCode.containsKey(d.id());
            boolean viaType = !listed && asCode.containsKey(type);
            List<String> others = notAsCode.getOrDefault(d.id(), List.of());
            if (listed) {
                kept.add(d.id());
                if (!others.isEmpty()) {
                    shared.add(d.id() + " -- brought in for " + String.join(", ", asCode.get(d.id())) + "; "
                            + String.join(", ", others) + " lists it too");
                }
            } else if (viaType && !others.isEmpty() && !d.kind().equals("type")) {
                out.add(d.id());
                cutOut.add(d.id() + " -- belongs to " + String.join(", ", others) + ", taken out of " + type);
            } else if (viaType) {
                kept.add(d.id());
            } else if (!others.isEmpty()) {
                out.add(d.id());
            }
        }
        // A file comes in when it holds something kept; in it, what was taken out goes and the rest stays.
        Set<String> landing = new LinkedHashSet<>();
        for (String id : kept) landing.add(inventory.get(id).path());
        List<String> unattributed = new ArrayList<>();
        for (Declaration d : inventory.values()) {
            if (landing.contains(d.path()) && !kept.contains(d.id()) && !out.contains(d.id())) {
                kept.add(d.id());
                unattributed.add(d.id());
            }
        }

        Map<String, CompilationUnit> units = units(source);
        Map<String, String> files = new TreeMap<>();
        Map<String, List<String>> provenance = new TreeMap<>();
        Set<String> landingTypes = new LinkedHashSet<>();
        for (Declaration d : inventory.values()) {
            if (d.kind().equals("type") && kept.contains(d.id())) landingTypes.add(simple(d.id()));
        }
        Set<String> sourceTypes = new LinkedHashSet<>();
        inventory.values().stream().filter(d -> d.kind().equals("type")).forEach(d -> sourceTypes.add(simple(d.id())));
        Set<String> keptMembers = new LinkedHashSet<>();
        kept.stream().filter(id -> !inventory.get(id).kind().equals("type")).forEach(id -> keptMembers.add(id.substring(id.lastIndexOf('.') + 1)));
        Map<String, String> outMembers = new LinkedHashMap<>();   // member name -> the capability it belongs to
        for (String id : out) {
            Declaration d = inventory.get(id);
            String owner = String.join(", ", notAsCode.getOrDefault(id, List.of()));
            if (!d.kind().equals("type")) outMembers.putIfAbsent(id.substring(id.lastIndexOf('.') + 1), owner);
        }
        List<String> problems = new ArrayList<>();
        for (String path : landing) {
            CompilationUnit cu = units.get(path);
            LexicalPreservingPrinter.setup(cu);
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                String id = idOf(t, inventory, path);
                if (id != null && out.contains(id)) {
                    t.remove();
                    continue;
                }
                for (BodyDeclaration<?> m : new ArrayList<>(t.getMembers())) {
                    String name = memberName(m);
                    if (name != null && id != null && out.contains(id + "." + name)) m.remove();
                }
            }
            // What the kept code still uses that does not come in.
            for (String used : usedMembers(cu)) {
                if (outMembers.containsKey(used) && !keptMembers.contains(used)) {
                    problems.add(path + " still uses " + used + ", which belongs to " + outMembers.get(used)
                            + " -- not brought in");
                }
            }
            for (String used : usedTypes(cu)) {
                if (sourceTypes.contains(used) && !landingTypes.contains(used)) {
                    problems.add(path + " uses " + used + ", which is not brought in"
                            + owners(used, inventory, asCode, notAsCode));
                }
            }
            for (ImportDeclaration i : new ArrayList<>(cu.getImports())) {
                String name = i.getName().getIdentifier();
                if (!i.isAsterisk() && !i.isStatic() && sourceTypes.contains(name) && !landingTypes.contains(name)
                        && !usedTypes(cu).contains(name)) i.remove();
            }
            files.put(path, LexicalPreservingPrinter.print(cu));
            List<String> serves = new ArrayList<>();
            for (Capability c : capabilities) {
                if (mode.getOrDefault(c.id(), Mode.LEAVE_OUT) != Mode.CODE) continue;
                if (c.declarations().stream().map(inventory::get).anyMatch(d -> d != null && d.path().equals(path))) serves.add(c.name());
            }
            provenance.put(path, serves);
        }
        return new Result(files, provenance, cutOut, shared, unattributed, problems.stream().distinct().toList());
    }

    private static String owners(String type, Map<String, Declaration> inventory, Map<String, List<String>> asCode,
                                 Map<String, List<String>> notAsCode) {
        for (Declaration d : inventory.values()) {
            if (d.kind().equals("type") && simple(d.id()).equals(type)) {
                List<String> o = notAsCode.getOrDefault(d.id(), List.of());
                return o.isEmpty() ? " (no capability lists it)" : " (it belongs to " + String.join(", ", o) + ")";
            }
        }
        return "";
    }

    private static Map<String, CompilationUnit> units(Map<String, String> source) {
        Map<String, CompilationUnit> out = new TreeMap<>();
        JavaParser parser = parser();
        source.forEach((path, text) -> {
            if (!path.endsWith(".java") || !path.contains(MAIN)) return;
            parser.parse(text).getResult().ifPresent(cu -> out.put(path, cu));
        });
        return out;
    }

    private static String idOf(TypeDeclaration<?> t, Map<String, Declaration> inventory, String path) {
        String simple = typeName(t, false), qualified = typeName(t, true);
        Declaration d = inventory.get(simple);
        if (d != null && d.path().equals(path)) return simple;
        d = inventory.get(qualified);
        return d != null ? qualified : null;
    }

    /** {@code Outer.Inner}, or with its package when {@code qualified}. */
    private static String typeName(TypeDeclaration<?> t, boolean qualified) {
        String name = t.getNameAsString();
        Node n = t.getParentNode().orElse(null);
        while (n instanceof TypeDeclaration<?> outer) {
            name = outer.getNameAsString() + "." + name;
            n = outer.getParentNode().orElse(null);
        }
        if (!qualified) return name;
        String pkg = t.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration).map(p -> p.getNameAsString()).orElse("");
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    private static String simple(String typeId) {
        return typeId.substring(typeId.lastIndexOf('.') + 1);
    }

    private static String memberName(BodyDeclaration<?> m) {
        if (m instanceof CallableDeclaration<?> c) return c.getNameAsString();
        if (m instanceof FieldDeclaration f) return f.getVariables().isEmpty() ? null : f.getVariable(0).getNameAsString();
        return null;
    }

    /** A declaration's first line, without its body or comments: what it is, for a person or a model to read. */
    private static String head(Node n) {
        if (n instanceof TypeDeclaration<?> t) {
            StringBuilder sb = new StringBuilder();
            t.getAnnotations().forEach(a -> sb.append(a).append(' '));
            String kind = t.isEnumDeclaration() ? "enum" : t.isRecordDeclaration() ? "record"
                    : t.isClassOrInterfaceDeclaration() && t.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
            return sb.append(kind).append(' ').append(t.getNameAsString()).toString();
        }
        if (n instanceof CallableDeclaration<?> c) return c.getDeclarationAsString(true, true, true);
        if (n instanceof FieldDeclaration f) {
            StringBuilder sb = new StringBuilder();
            f.getModifiers().forEach(m -> sb.append(m.getKeyword().asString()).append(' '));
            sb.append(f.getElementType()).append(' ');
            sb.append(String.join(", ", f.getVariables().stream().map(VariableDeclarator::getNameAsString).toList()));
            return sb.toString();
        }
        return n.toString().replaceAll("\\s+", " ").strip();
    }

    private static Set<String> usedMembers(CompilationUnit cu) {
        Set<String> out = new LinkedHashSet<>();
        cu.findAll(MethodCallExpr.class).forEach(c -> out.add(c.getNameAsString()));
        cu.findAll(MethodReferenceExpr.class).forEach(r -> out.add(r.getIdentifier()));
        cu.findAll(FieldAccessExpr.class).forEach(f -> out.add(f.getNameAsString()));
        cu.findAll(NameExpr.class).forEach(n -> out.add(n.getNameAsString()));
        // A name the file itself declares (a local, a parameter, its own field) is not a use of the source's member.
        cu.findAll(VariableDeclarator.class).forEach(v -> out.remove(v.getNameAsString()));
        cu.findAll(com.github.javaparser.ast.body.Parameter.class).forEach(p -> out.remove(p.getNameAsString()));
        cu.findAll(CallableDeclaration.class).forEach(c -> out.remove(c.getNameAsString()));
        return out;
    }

    private static Set<String> usedTypes(CompilationUnit cu) {
        Set<String> out = new LinkedHashSet<>();
        cu.findAll(ClassOrInterfaceType.class).forEach(t -> out.add(t.getNameAsString()));
        cu.findAll(NameExpr.class).forEach(n -> out.add(n.getNameAsString()));
        cu.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).forEach(a -> out.add(a.getNameAsString()));
        cu.findAll(TypeDeclaration.class).forEach(t -> out.remove(t.getNameAsString()));
        return out;
    }
}
