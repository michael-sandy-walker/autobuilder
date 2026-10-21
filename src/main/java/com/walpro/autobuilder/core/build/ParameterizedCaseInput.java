package com.walpro.autobuilder.core.build;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EA: the INPUT of a failing parameterized test case, read from the test's own source.
 *
 * <p>A test runner reports a parameterized case by its method and invocation index --
 * {@code check(String, String)[10]} -- and nothing of the arguments. Receiver-questions A/B,
 * 2026-09-27: case [10] of the specification failed in five of six runs; the evaluator was shown
 * "[10] failed: Expecting Optional to contain a value but it was empty" and guessed at
 * "the specific JDK type", and the planner injected a subgoal to read the test file and document
 * case [10] -- one prompt after a prompt that showed the test source, rows included. The fact that
 * decides the fix (the row's signature carried a generic type) was on the page as the tenth of ten
 * rows and never joined to "[10]". The join is structure's to make: the row is in the source on disk.
 *
 * <p>Answers only where the source states the rows literally: an inline row source (named in
 * {@link TestAssertionVocabulary}) whose values are literals, one per invocation in order.
 * Anything else -- rows produced by a method, a file, an enum, several sources on one test, a
 * header row, a count that does not reach the index -- is empty. An empty answer loses the fact;
 * a wrong row would invent one.
 */
public final class ParameterizedCaseInput {

    /**
     * How a failing invocation's row is labelled wherever it is shown. It is DATA the test hands to the
     * code under test, and must read as such: labelled "ran with:", an archived compiler message in the row
     * was read by the model as a real error of the project, and it advised fixing a file that does not
     * exist (receiver-questions run 2026-09-29_17-23-59).
     */
    public static final String LABEL = "test input given to the code under test: ";

    /** {@code name(Params)[N]}: the runner's report of one invocation. */
    private static final Pattern INVOCATION = Pattern.compile("^([\\w$]+)(?:\\(([^)]*)\\))?\\[(\\d+)]$");

    private static final String[] TEST_SOURCE_ROOTS = {"src/test/java"};

    private ParameterizedCaseInput() {
    }

    /**
     * The row that the reported invocation ran with, as written in the source (a delimited row stays
     * one string). Empty unless the method is found and its rows are literal.
     */
    public static Optional<String> of(Path projectRoot, String className, String reportedMethod) {
        if (projectRoot == null || className == null || reportedMethod == null) return Optional.empty();
        Matcher m = INVOCATION.matcher(reportedMethod.strip());
        if (!m.matches()) return Optional.empty();
        String name = m.group(1);
        int arity = m.group(2) == null ? -1 : (m.group(2).isBlank() ? 0 : m.group(2).split(",").length);
        int index = Integer.parseInt(m.group(3));
        Optional<CompilationUnit> unit = parse(projectRoot, className);
        if (unit.isEmpty()) return Optional.empty();
        String simple = className.substring(className.lastIndexOf('.') + 1);
        String declaring = simple.contains("$") ? simple.substring(simple.lastIndexOf('$') + 1) : simple;
        List<MethodDeclaration> candidates = unit.get().findAll(MethodDeclaration.class, md ->
                md.getNameAsString().equals(name)
                        && md.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                        .map(t -> t.getNameAsString().equals(declaring)).orElse(false));
        // The reported parameter list only separates overloads; a generic parameter's commas make
        // its count unreliable, so it is not asked of a name that is already unique.
        if (candidates.size() > 1 && arity >= 0) {
            candidates = candidates.stream().filter(md -> md.getParameters().size() == arity).toList();
        }
        if (candidates.size() != 1) return Optional.empty();
        return rows(candidates.get(0)).filter(r -> index >= 1 && index <= r.size()).map(r -> r.get(index - 1));
    }

    private static Optional<List<String>> rows(MethodDeclaration md) {
        List<AnnotationExpr> sources = md.getAnnotations().stream()
                .filter(a -> a.getName().getIdentifier().endsWith(TestAssertionVocabulary.ARGUMENT_SOURCE_SUFFIX))
                .toList();
        if (sources.size() != 1) return Optional.empty();
        AnnotationExpr source = sources.get(0);
        if (!TestAssertionVocabulary.LITERAL_ROW_SOURCES.contains(source.getName().getIdentifier())) return Optional.empty();
        List<Expression> values = new ArrayList<>();
        if (source instanceof SingleMemberAnnotationExpr single) {
            values.add(single.getMemberValue());
        } else if (source instanceof NormalAnnotationExpr normal) {
            // A header row shifts every index by one; not reproduced, so not answered.
            if (normal.getPairs().stream().anyMatch(p -> p.getNameAsString().equals(TestAssertionVocabulary.HEADER_ROW_ATTRIBUTE))) {
                return Optional.empty();
            }
            // Exactly one attribute may carry the rows; a second (a text block beside value) would
            // make the invocation order something this reader does not reproduce.
            var carriers = normal.getPairs().stream()
                    .filter(p -> !TestAssertionVocabulary.NON_ROW_ATTRIBUTES.contains(p.getNameAsString()))
                    .toList();
            if (carriers.size() != 1) return Optional.empty();
            if (carriers.get(0).getNameAsString().equals(TestAssertionVocabulary.TEXT_BLOCK_ROWS)) {
                return textBlockRows(carriers.get(0).getValue());
            }
            values.add(carriers.get(0).getValue());
        } else {
            return Optional.empty();
        }
        List<String> rows = new ArrayList<>();
        for (Expression v : values) {
            List<Expression> items = v instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(v);
            for (Expression item : items) {
                if (item instanceof StringLiteralExpr s) {
                    rows.add(s.asString().strip());
                } else if (item.isLiteralExpr() && !(item instanceof TextBlockLiteralExpr)) {
                    rows.add(item.toString());
                } else {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(rows);
    }

    /** A text block's rows are its non-blank lines that are not comments. */
    private static Optional<List<String>> textBlockRows(Expression value) {
        if (!(value instanceof TextBlockLiteralExpr tb)) return Optional.empty();
        return Optional.of(tb.asString().lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith(TestAssertionVocabulary.TEXT_BLOCK_COMMENT)).toList());
    }

    private static Optional<CompilationUnit> parse(Path projectRoot, String className) {
        String outer = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        for (Path root : java.util.Arrays.stream(TEST_SOURCE_ROOTS)
                .flatMap(r -> ProjectLayout.underEveryModule(projectRoot, r).stream()).toList()) {   // T4-EC: every module's
            Path file = root.resolve(outer.replace('.', '/') + ".java");
            if (!Files.isRegularFile(file)) continue;
            try {
                JavaParser parser = new JavaParser(new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
                return parser.parse(Files.readString(file)).getResult();
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
