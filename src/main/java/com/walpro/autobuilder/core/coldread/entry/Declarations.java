package com.walpro.autobuilder.core.coldread.entry;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * T4-EQ: what a project's source DECLARES about how it is used, read from structure -- never observed, never a model's.
 *
 * <p>Found 2026-11-16 by building labelmaker from its reverse-engineered seed alone, with the specification withheld:
 * four of five answers wrote {@code checkChar} and {@code isValid} as instance methods (the seed wrote
 * {@code Labeler.checkChar(<body>)} and {@code Labeler.next(<postcode>)} alike, and only {@code next} is one), and
 * the fifth guessed the check alphabet, which the seed named only as "the 19-letter alphabet". Observations show what
 * a way in ANSWERED; they cannot show whether it belongs to the class, or the value of a constant every answer
 * depends on. The source states both.
 *
 * <p>Two kinds of fact, each a line:
 * <ul>
 * <li><b>Shape</b> (library-style projects, whose ways in ARE their methods): each publicly reachable type -- what
 * kind it is and how it is created -- and each public method with its return type, parameters, and whether it is
 * called on the type or on an instance.</li>
 * <li><b>Values the code holds</b> (any project): the constants of an enum in their declared order, and every
 * {@code static final} field whose value is written as a literal (a string, a number, a character, an array of
 * them, or a list, set or map made only of them), whatever its visibility -- a private alphabet is still what every check character is drawn from.</li>
 * </ul>
 * A field computed from other code is not a written value and is left out; so is anything longer than
 * {@link #VALUE_CHARS}. At most {@link #MAX_LINES} lines, shape first.
 */
@Slf4j
public final class Declarations {

    private static final String MAIN = "src/main/java/";
    static final int VALUE_CHARS = 200;
    static final int MAX_LINES = 40;

    private Declarations() {
    }

    /** The declared shape (when {@code shape}) and the written values of the sources under {@code root}; empty when there are none. */
    public static List<String> of(Path root, boolean shape) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<Path> sources;
        try (Stream<Path> s = Files.walk(root)) {
            sources = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> root.relativize(p).toString().replace('\\', '/').contains(MAIN))
                    .sorted().toList();
        } catch (IOException e) {
            log.warn("T4-EQ: the declarations of {} could not be read -- {}", root, e.getMessage());
            return List.of();
        }
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        List<String> shapes = new ArrayList<>(), values = new ArrayList<>();
        for (Path p : sources) {
            CompilationUnit cu;
            try {
                cu = parser.parse(Files.readString(p)).getResult().orElse(null);
            } catch (IOException e) {
                continue;
            }
            if (cu == null) {
                log.warn("T4-EQ: {} does not parse; nothing it declares is stated", root.relativize(p));
                continue;
            }
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                String name = LibraryStyleAdapter.nested(t);
                if (t instanceof EnumDeclaration e && !e.getEntries().isEmpty()) {
                    values.add("enum " + name + " -- its constants, in this order: "
                            + String.join(", ", e.getEntries().stream().map(EnumConstantDeclaration::getNameAsString).toList()));
                }
                for (FieldDeclaration f : t.getFields()) {
                    boolean constant = (f.isStatic() && f.isFinal())
                            || (t instanceof ClassOrInterfaceDeclaration c && c.isInterface());
                    if (!constant) continue;
                    for (VariableDeclarator v : f.getVariables()) {
                        Expression init = v.getInitializer().orElse(null);
                        if (init == null || !written(init)) continue;
                        String value = init.toString().replaceAll("\\s+", " ");
                        if (value.length() > VALUE_CHARS) continue;
                        values.add("constant " + name + "." + v.getNameAsString() + " = " + value);
                    }
                }
                if (!shape || !LibraryStyleAdapter.publiclyReachable(t)) continue;
                String made = madeBy(t, name);
                if (made != null) shapes.add(made);
                for (MethodDeclaration m : t.getMethods()) {
                    if (!m.isPublic() || m.getBody().isEmpty()) continue;
                    if (m.isStatic() && m.getNameAsString().equals("main")) continue;
                    String signature = m.getType().asString() + " " + name + "." + m.getNameAsString() + "("
                            + String.join(", ", m.getParameters().stream().map(x -> x.getType().asString() + " " + x.getNameAsString()).toList()) + ")";
                    shapes.add(m.isStatic() ? "static " + signature + " -- called on " + name + " itself"
                            : signature + " -- called on an instance of " + name + ", not on " + name + " itself");
                }
            }
        }
        List<String> out = new ArrayList<>(shapes);
        out.addAll(values);
        if (out.size() > MAX_LINES) {
            log.warn("T4-EQ: {} declaration(s) read, the first {} stated", out.size(), MAX_LINES);
            return List.copyOf(out.subList(0, MAX_LINES));
        }
        return out;
    }

    /**
     * How a type is created by its users; null when they do not create it: an enum (its constants are stated with the
     * values), an interface, an abstract class, a class without a public constructor, or one whose public methods are
     * all static -- a holder of functions is not made, its implicit constructor notwithstanding (structdiff's
     * {@code StructuralDiffUtils}: "created with new StructuralDiffUtils()" invites a builder to make one).
     */
    private static String madeBy(TypeDeclaration<?> t, String name) {
        if (t instanceof EnumDeclaration) return null;
        if (t instanceof RecordDeclaration r) {
            return "record " + name + "(" + String.join(", ", r.getParameters().stream()
                    .map(x -> x.getType().asString() + " " + x.getNameAsString()).toList()) + ")";
        }
        if (!(t instanceof ClassOrInterfaceDeclaration c) || c.isInterface() || c.isAbstract()) return null;
        List<MethodDeclaration> open = c.getMethods().stream().filter(MethodDeclaration::isPublic).toList();
        if (!open.isEmpty() && open.stream().allMatch(MethodDeclaration::isStatic)) return null;
        List<ConstructorDeclaration> declared = c.getConstructors();
        if (declared.isEmpty()) return "class " + name + " -- created with new " + name + "()";
        List<String> made = declared.stream().filter(ConstructorDeclaration::isPublic)
                .map(k -> "new " + name + "(" + String.join(", ", k.getParameters().stream()
                        .map(x -> x.getType().asString() + " " + x.getNameAsString()).toList()) + ")").toList();
        return made.isEmpty() ? null : "class " + name + " -- created with " + String.join(" or ", made);
    }

    /** The collection factories whose result is only the values written into them. */
    private static final java.util.Set<String> COLLECTIONS = java.util.Set.of("List.of", "Set.of", "Map.of", "Arrays.asList");

    /**
     * Whether an initializer is a value written out: a literal, a negated one, an array of them, or a collection made
     * only of them ({@link #COLLECTIONS}; criterion-status's {@code RESPONSE_STATUSES = Set.of(200, 201, ...)} decides
     * which numbers count as statuses, as an alphabet decides check characters). A compiled pattern or any other call
     * is how the code works, not a value it holds.
     */
    static boolean written(Expression e) {
        if (e instanceof MethodCallExpr call) {
            String scope = call.getScope().map(Expression::toString).orElse("");
            scope = scope.substring(scope.lastIndexOf('.') + 1);   // java.util.Set.of is Set.of
            return COLLECTIONS.contains(scope + "." + call.getNameAsString()) && !call.getArguments().isEmpty()
                    && call.getArguments().stream().allMatch(Declarations::written);
        }
        if (e instanceof NullLiteralExpr) return false;
        if (e instanceof LiteralExpr) return true;
        if (e instanceof UnaryExpr u) return u.getOperator() == UnaryExpr.Operator.MINUS && u.getExpression() instanceof LiteralExpr;
        if (e instanceof ArrayInitializerExpr a) return !a.getValues().isEmpty() && a.getValues().stream().allMatch(Declarations::written);
        if (e instanceof ArrayCreationExpr a) return a.getInitializer().map(Declarations::written).orElse(false);
        return false;
    }
}
