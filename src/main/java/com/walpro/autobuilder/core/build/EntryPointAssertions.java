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
                out.add(new MethodView(cls + "." + md.getNameAsString(), md, byName));
            }
        }
        return anyEcosystem ? out : null;
    }

    /** One test method, with the helpers of its own class that it calls. */
    record MethodView(String name, MethodDeclaration method, Map<String, MethodDeclaration> helpers) {

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
        private static boolean callsIn(MethodDeclaration md, String type, String name) {
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
            }
            return false;
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

        /** The assertions this test makes, VALUE among them when one compares a returned value. */
        Set<String> assertionsFor(String entryMethod) {
            Set<String> seen = new LinkedHashSet<>();
            for (MethodCallExpr c : method.findAll(MethodCallExpr.class)) {
                String n = c.getNameAsString();
                if (TestAssertionVocabulary.VALUE_ASSERTIONS.contains(n)
                        || TestAssertionVocabulary.VALUE_CHAIN_STEPS.contains(n)) {
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
