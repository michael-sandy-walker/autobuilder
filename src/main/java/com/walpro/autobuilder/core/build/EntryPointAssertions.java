package com.walpro.autobuilder.core.build;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-CQ: does the project's own test suite call an entry point AND assert a value it returns? Read
 * from the test sources, deterministically, for the obligations that name an entry point.
 *
 * <p><b>Why this exists.</b> structdiff run 2026-09-25_04-35-01 closed GOAL_FULFILLED at 6/11: the
 * completion verifier, a model reading the source, was handed 26 requirements in one call and marked
 * all 26 met -- "A test must assert what the entry point actually returns" and "DiffSummarizer.summarize
 * ... is covered by a test" among them -- while its own evidence listed the test that covered it as
 * {@code summarizeReturnsNonEmptyString}, which asserts only that a string came back. The seed states
 * the requirement; the test source answers it; a model was the one reader that could get it wrong.
 *
 * <p><b>What counts.</b> A test method calls {@code Type.method(...)} itself or through a helper of
 * its own class, and the TEST method holds a value assertion ({@link TestAssertionVocabulary}), or a
 * truth assertion wrapped around that call (a boolean entry point). Only sources that import one of
 * the vocabulary's ecosystems are read; a project written against none of them gets no opinion, and
 * closure falls through to the verifier exactly as before.
 *
 * <p><b>Only ever a refusal.</b> Used at the completion edge to BLOCK, never to confirm: a test that
 * passes this check can still assert the wrong thing, and that stays the verifier's question.
 */
public final class EntryPointAssertions {

    private EntryPointAssertions() {
    }

    /** {@code Type.method(} in an obligation's text. */
    private static final Pattern ENTRY_POINT = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\.([a-z][A-Za-z0-9_]*)\\(");

    /** What the suite does with one entry point. */
    public record Finding(String type, String method, List<String> callingTests, Set<String> assertionsSeen) {
        public boolean valueAsserted() {
            return !callingTests.isEmpty() && assertionsSeen.contains(VALUE);
        }

        public String describe() {
            if (callingTests.isEmpty()) return type + "." + method + " is called by no test";
            Set<String> other = new LinkedHashSet<>(assertionsSeen);
            other.remove(VALUE);
            return type + "." + method + " is called only in " + String.join(", ", callingTests)
                    + (other.isEmpty() ? ", which assert nothing" : ", which assert only " + String.join(", ", other))
                    + " -- none asserts a value it returns";
        }
    }

    private static final String VALUE = "\u0000value";

    /**
     * The obligations among {@code obligations} that name an entry point and speak of tests, whose
     * entry point no test calls and value-asserts -- each with what the suite does instead. Empty when
     * every such obligation is met, or when no test source is written against a known ecosystem.
     */
    public static Map<String, Finding> unmet(List<String> obligations, Map<String, String> files) {
        Map<String, Finding> out = new LinkedHashMap<>();
        if (obligations == null || files == null) return out;
        List<MethodView> tests = testMethods(files);
        if (tests == null) return out;                                // no ecosystem this table knows
        for (String obligation : obligations) {
            if (obligation == null || !obligation.toLowerCase(java.util.Locale.ROOT).contains("test")) continue;
            Matcher m = ENTRY_POINT.matcher(obligation);
            if (!m.find()) continue;
            Finding f = inspect(m.group(1), m.group(2), tests);
            if (!f.valueAsserted()) out.put(obligation, f);
        }
        return out;
    }

    /**
     * The entry points named by {@code obligations} that the tests in {@code testNames} ({@code
     * Class.method}) call -- each with only those tests as its callers, whatever they assert. Where a
     * candidate's own new tests FAIL, these are the calls whose returned value would tell the model
     * what to assert (participation run 2026-09-25_11-45-07: one test was rewritten nine times, each
     * failure reporting one field while four others stayed guesses).
     */
    public static Map<String, Finding> calledBy(List<String> obligations, Map<String, String> files,
                                                Set<String> testNames) {
        Map<String, Finding> out = new LinkedHashMap<>();
        if (obligations == null || files == null || testNames == null || testNames.isEmpty()) return out;
        List<MethodView> tests = testMethods(files);
        if (tests == null) return out;
        List<MethodView> among = tests.stream().filter(t -> testNames.contains(t.name())).toList();
        for (String obligation : obligations) {
            if (obligation == null || !obligation.toLowerCase(java.util.Locale.ROOT).contains("test")) continue;
            Matcher m = ENTRY_POINT.matcher(obligation);
            if (!m.find()) continue;
            Finding f = inspect(m.group(1), m.group(2), among);
            if (!f.callingTests().isEmpty()) out.putIfAbsent(f.type() + "." + f.method(), f);
        }
        return out;
    }

    static Finding inspect(String type, String method, List<MethodView> tests) {
        List<String> calling = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MethodView t : tests) {
            if (!t.calls(type, method)) continue;
            calling.add(t.name());
            seen.addAll(t.assertionsFor(method));
        }
        return new Finding(type, method, calling, seen);
    }

    /** The test methods of every test source written against a known ecosystem; null when there is none. */
    static List<MethodView> testMethods(Map<String, String> files) {
        List<MethodView> out = new ArrayList<>();
        boolean anyEcosystem = false;
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        Map<String, Map<String, String>> returns = declaredReturnTypes(files, parser);
        for (Map.Entry<String, String> e : files.entrySet()) {
            String path = e.getKey().replace('\\', '/');
            if (!path.endsWith(".java") || !(path.startsWith("src/test/") || path.contains("/src/test/"))) continue;
            Optional<CompilationUnit> cu = parser.parse(e.getValue()).getResult();
            if (cu.isEmpty()) continue;
            boolean gated = cu.get().getImports().stream().anyMatch(i -> TestAssertionVocabulary.GATE_IMPORT_PREFIXES
                    .stream().anyMatch(p -> i.getNameAsString().startsWith(p)));
            if (!gated) continue;
            anyEcosystem = true;
            Map<String, MethodDeclaration> byName = new LinkedHashMap<>();
            for (MethodDeclaration md : cu.get().findAll(MethodDeclaration.class)) byName.putIfAbsent(md.getNameAsString(), md);
            for (MethodDeclaration md : cu.get().findAll(MethodDeclaration.class)) {
                boolean isTest = md.getAnnotations().stream()
                        .anyMatch(a -> TestAssertionVocabulary.TEST_ANNOTATIONS.contains(a.getNameAsString()));
                if (!isTest) continue;
                String cls = md.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                        .map(c -> c.getNameAsString()).orElse("?");
                out.add(new MethodView(cls + "." + md.getNameAsString(), md, byName, returns));
            }
        }
        return anyEcosystem ? out : null;
    }

    /**
     * What each method of each type the project declares returns, by simple name: {@code type -> method ->
     * return type}, a record's accessors included. It is how a call is attributed to a type when its
     * receiver is itself a call ({@code Windows.empty().grant(w)}) or a {@code var} local -- the natural
     * style for immutable values, which the syntactic cases alone credited to no type. Shown by
     * participation-b run 2026-09-25_17-43-13: every grant was tested as {@code Windows.empty().grant(w)},
     * the yardstick scored 12/12, and closure refused "grant is called by no test" for twelve minutes
     * until the run halted. A name whose overloads return different types maps to none.
     */
    static Map<String, Map<String, String>> declaredReturnTypes(Map<String, String> files, JavaParser parser) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!e.getKey().endsWith(".java")) continue;
            Optional<CompilationUnit> cu = parser.parse(e.getValue()).getResult();
            if (cu.isEmpty()) continue;
            for (com.github.javaparser.ast.body.TypeDeclaration<?> td : cu.get().findAll(com.github.javaparser.ast.body.TypeDeclaration.class)) {
                Map<String, String> methods = out.computeIfAbsent(td.getNameAsString(), k -> new LinkedHashMap<>());
                if (td instanceof com.github.javaparser.ast.body.RecordDeclaration rd) {
                    rd.getParameters().forEach(p -> methods.merge(p.getNameAsString(), erased(p.getType().asString()), EntryPointAssertions::same));
                }
                for (MethodDeclaration md : td.getMethods()) {
                    methods.merge(md.getNameAsString(), erased(md.getType().asString()), EntryPointAssertions::same);
                }
            }
        }
        return out;
    }

    private static String same(String a, String b) {
        return a.equals(b) ? a : "";
    }

    private static String erased(String type) {
        String t = type.replaceAll("<.*>", "").trim();
        int dot = t.lastIndexOf('.');
        return dot < 0 ? t : t.substring(dot + 1);
    }

    /** One test method, with the helpers of its own class that it calls. */
    record MethodView(String name, MethodDeclaration method, Map<String, MethodDeclaration> helpers,
                      Map<String, Map<String, String>> returns) {

        boolean calls(String type, String name) {
            if (callsIn(method, type, name)) return true;
            for (MethodCallExpr c : method.findAll(MethodCallExpr.class)) {
                if (c.getScope().isPresent()) continue;
                MethodDeclaration h = helpers.get(c.getNameAsString());
                if (h != null && h != method && callsIn(h, type, name)) return true;
            }
            return false;
        }

        /**
         * A static call on the type, a call on a new instance of it, or a call on a variable, field or
         * parameter declared with it -- an instance entry point is still that entry point. Missing one
         * of these would turn a covered entry point into a refusal, which walls the run.
         */
        private boolean callsIn(MethodDeclaration md, String type, String name) {
            Set<String> ofType = namesDeclaredAs(md, type);
            for (MethodCallExpr c : md.findAll(MethodCallExpr.class)) {
                if (!c.getNameAsString().equals(name) || c.getScope().isEmpty()) continue;
                var scopeExpr = c.getScope().get();
                String scope = scopeExpr.toString();
                if (scope.equals(type) || scope.endsWith("." + type)) return true;
                if (scopeExpr.isObjectCreationExpr()
                        && scopeExpr.asObjectCreationExpr().getType().getNameAsString().equals(type)) return true;
                if (scopeExpr.isNameExpr() && ofType.contains(scope)) return true;
                if (scopeExpr.isFieldAccessExpr() && ofType.contains(scopeExpr.asFieldAccessExpr().getNameAsString())) return true;
                if (type.equals(typeOf(scopeExpr, md, 0))) return true;
            }
            return false;
        }

        /**
         * The simple name of the type an expression evaluates to, as far as the project's own
         * declarations say; null when they do not. A call's type is what its receiver's type declares
         * that method to return; a {@code var} local's is its initializer's.
         */
        private String typeOf(com.github.javaparser.ast.expr.Expression e, MethodDeclaration md, int depth) {
            if (e == null || depth > 12) return null;
            if (e.isEnclosedExpr()) return typeOf(e.asEnclosedExpr().getInner(), md, depth + 1);
            if (e.isCastExpr()) return erased(e.asCastExpr().getType().asString());
            if (e.isObjectCreationExpr()) return e.asObjectCreationExpr().getType().getNameAsString();
            if (e.isNameExpr()) {
                String n = e.asNameExpr().getNameAsString();
                var root = md.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                        .map(c -> (com.github.javaparser.ast.Node) c).orElse(md);
                for (var v : root.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
                    if (!v.getNameAsString().equals(n)) continue;
                    if (!v.getType().isVarType()) return erased(v.getType().asString());
                    return v.getInitializer().map(i -> typeOf(i, md, depth + 1)).orElse(null);
                }
                for (var p : root.findAll(com.github.javaparser.ast.body.Parameter.class)) {
                    if (p.getNameAsString().equals(n)) return erased(p.getType().asString());
                }
                return null;
            }
            if (e.isMethodCallExpr()) {
                var call = e.asMethodCallExpr();
                String owner;
                if (call.getScope().isEmpty()) {
                    owner = md.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                            .map(c -> c.getNameAsString()).orElse(null);
                } else {
                    var scope = call.getScope().get();
                    String asType = scope.isNameExpr() && returns.containsKey(scope.toString()) ? scope.toString() : null;
                    owner = asType != null ? asType : typeOf(scope, md, depth + 1);
                }
                if (owner == null) return null;
                String r = returns.getOrDefault(owner, Map.of()).get(call.getNameAsString());
                return r == null || r.isEmpty() ? null : r;
            }
            return null;
        }

        private static Set<String> namesDeclaredAs(MethodDeclaration md, String type) {
            Set<String> names = new LinkedHashSet<>();
            var root = md.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                    .map(n -> (com.github.javaparser.ast.Node) n).orElse(md);
            for (var v : root.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
                if (v.getType().asString().replaceAll("<.*>", "").equals(type)) names.add(v.getNameAsString());
            }
            for (var p : root.findAll(com.github.javaparser.ast.body.Parameter.class)) {
                if (p.getType().asString().replaceAll("<.*>", "").equals(type)) names.add(p.getNameAsString());
            }
            return names;
        }

        /** Whether the chain {@code step} is called on starts at a fluent assertion's entry call. */
        private static boolean startsAtAnAssertion(MethodCallExpr step) {
            var scope = step.getScope().orElse(null);
            while (scope instanceof MethodCallExpr call) {
                if (TestAssertionVocabulary.FLUENT_ENTRIES.contains(call.getNameAsString())) return true;
                scope = call.getScope().orElse(null);
            }
            return false;
        }

        /** The assertions this test makes, VALUE among them when one compares a returned value. */
        Set<String> assertionsFor(String entryMethod) {
            Set<String> seen = new LinkedHashSet<>();
            for (MethodCallExpr c : method.findAll(MethodCallExpr.class)) {
                String n = c.getNameAsString();
                if (TestAssertionVocabulary.VALUE_ASSERTIONS.contains(n)
                        || TestAssertionVocabulary.VALUE_CHAIN_STEPS.contains(n)) {
                    seen.add(n);
                    seen.add(VALUE);
                } else if (TestAssertionVocabulary.VALUE_STEPS_OF_A_FLUENT_CHAIN.contains(n) && startsAtAnAssertion(c)) {
                    seen.add(n);
                    seen.add(VALUE);
                } else if (TestAssertionVocabulary.TRUTH_ASSERTIONS.contains(n)) {
                    seen.add(n);
                    boolean wrapsTheCall = c.getArguments().stream().findFirst()
                            .map(a -> a.findAll(MethodCallExpr.class).stream()
                                    .anyMatch(inner -> inner.getNameAsString().equals(entryMethod))
                                    || (a instanceof MethodCallExpr mc && mc.getNameAsString().equals(entryMethod)))
                            .orElse(false);
                    if (wrapsTheCall) seen.add(VALUE);
                } else if (n.startsWith("assert")) {
                    seen.add(n);
                }
            }
            return seen;
        }
    }
}
