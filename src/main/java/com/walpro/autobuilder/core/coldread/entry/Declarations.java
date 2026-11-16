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
 * {@code static final} field whose value is written as a literal (a string, a number, a character, or an array of
 * them), whatever its visibility -- a private alphabet is still what every check character is drawn from.</li>
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
            if (cu == null) continue;
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

    /** How a type is created by its users; null when they do not create it (an interface, an abstract class, no public constructor). */
    private static String madeBy(TypeDeclaration<?> t, String name) {
        if (t instanceof EnumDeclaration) return "enum " + name;
        if (t instanceof RecordDeclaration r) {
            return "record " + name + "(" + String.join(", ", r.getParameters().stream()
                    .map(x -> x.getType().asString() + " " + x.getNameAsString()).toList()) + ")";
        }
        if (!(t instanceof ClassOrInterfaceDeclaration c) || c.isInterface() || c.isAbstract()) return null;
        List<ConstructorDeclaration> declared = c.getConstructors();
        if (declared.isEmpty()) return "class " + name + " -- created with new " + name + "()";
        List<String> open = declared.stream().filter(ConstructorDeclaration::isPublic)
                .map(k -> "new " + name + "(" + String.join(", ", k.getParameters().stream()
                        .map(x -> x.getType().asString() + " " + x.getNameAsString()).toList()) + ")").toList();
        return open.isEmpty() ? null : "class " + name + " -- created with " + String.join(" or ", open);
    }

    /** Whether an initializer is a value written out: a literal, a negated one, or an array of them. */
    static boolean written(Expression e) {
        if (e instanceof NullLiteralExpr) return false;
        if (e instanceof LiteralExpr) return true;
        if (e instanceof UnaryExpr u) return u.getOperator() == UnaryExpr.Operator.MINUS && u.getExpression() instanceof LiteralExpr;
        if (e instanceof ArrayInitializerExpr a) return !a.getValues().isEmpty() && a.getValues().stream().allMatch(Declarations::written);
        if (e instanceof ArrayCreationExpr a) return a.getInitializer().map(Declarations::written).orElse(false);
        return false;
    }
}
