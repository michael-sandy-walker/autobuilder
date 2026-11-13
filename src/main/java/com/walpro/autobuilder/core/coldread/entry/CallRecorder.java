package com.walpro.autobuilder.core.coldread.entry;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Call;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * T4-EQ: what a library-style claim's calls ANSWERED, recorded as the claim's test runs.
 *
 * <p>A run leaves its output and a request its response; a test leaves only that it passed. So a claim of this kind
 * had no answer in the account, and everything built on the answer -- wording a seed line from what was observed,
 * stating once what several lines are answered with -- had nothing to work from: measured on three projects, their
 * seeds were lists of some sixty instance lines with nothing stated once, and where the composer did state a rule the
 * values went missing.
 *
 * <p>The record is taken in the copy the claims run in, never in the project: every way in (a public method of a
 * publicly reachable type) is renamed and stood behind a method of its old name that notes the arguments, calls it,
 * and notes what came back or was raised. The claim's test is not touched, so what it compiles to and asserts is what
 * it was. Only a call made BY the claim's own test is kept -- not what a way in calls on the way, and not what the
 * assertions call to compare two values.
 *
 * <p>A copy that no longer builds with the record in it is put back as it was ({@link #restore}); the claims are then
 * judged without one, as before.
 */
final class CallRecorder {

    static final String PACKAGE = "revcalls", TYPE = "Recorded";
    private static final String RECORDED = PACKAGE + "." + TYPE;
    /** What a way in is renamed to; the method of its old name stands in front of it. */
    static final String BEHIND = "$recorded";
    private static final String MAIN = "src/main/java/";
    private static final String EXT = ".calls";

    private CallRecorder() {
    }

    /**
     * Stands a recording method in front of every way in of the tree's sources and adds the recorder beside them.
     *
     * @param calls       where the calls are written, one file per claim's test class
     * @param claimPrefix how a claim's test class is named: only calls made from such a class are kept
     * @return the sources as they were, by file, to put back; empty when the tree has no way in to record
     */
    static Map<Path, String> instrument(Path tree, Path calls, String claimPrefix) throws IOException {
        Map<Path, String> before = new LinkedHashMap<>();
        Path main = tree.resolve(MAIN);
        if (!Files.isDirectory(main)) return before;
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(main)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        for (Path source : sources) {
            String text = Files.readString(source);
            CompilationUnit cu = parser.parse(text).getResult().orElse(null);
            if (cu == null) continue;
            boolean changed = false;
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                if (!LibraryStyleAdapter.publiclyReachable(t) || t.isAnnotationDeclaration()) continue;
                if (t.isClassOrInterfaceDeclaration() && t.asClassOrInterfaceDeclaration().isInterface()) continue;
                for (MethodDeclaration m : new ArrayList<>(t.getMethods())) {
                    if (!m.isPublic() || m.getBody().isEmpty()) continue;
                    if (m.isStatic() && m.getNameAsString().equals("main")) continue;
                    BlockStmt front = parser.parseBlock(front(LibraryStyleAdapter.nested(t), m)).getResult().orElse(null);
                    if (front == null) continue;
                    MethodDeclaration behind = m.clone();
                    behind.setName(m.getNameAsString() + BEHIND);
                    behind.setAnnotations(new NodeList<>());
                    behind.removeComment();
                    NodeList<Modifier> modifiers = new NodeList<>(Modifier.privateModifier());
                    if (m.isStatic()) modifiers.add(Modifier.staticModifier());
                    behind.setModifiers(modifiers);
                    t.addMember(behind);
                    m.setBody(front);
                    changed = true;
                }
            }
            if (!changed) continue;
            before.put(source, text);
            Files.writeString(source, cu.toString());
        }
        if (before.isEmpty()) return before;
        Files.createDirectories(calls);
        Path recorder = main.resolve(PACKAGE).resolve(TYPE + ".java");
        Files.createDirectories(recorder.getParent());
        Files.writeString(recorder, recorder(calls, claimPrefix));
        before.put(recorder, null);
        return before;
    }

    /** Puts the sources back as {@link #instrument} found them. */
    static void restore(Map<Path, String> before) throws IOException {
        for (Map.Entry<Path, String> e : before.entrySet()) {
            if (e.getValue() == null) Files.deleteIfExists(e.getKey());
            else Files.writeString(e.getKey(), e.getValue());
        }
    }

    /** The calls the claim's test class made, in order; empty when none was recorded. */
    static List<Call> read(Path calls, String testClass) {
        List<Call> out = new ArrayList<>();
        Path file = calls.resolve(testClass + EXT);
        if (!Files.isRegularFile(file)) return out;
        try {
            for (String line : Files.readAllLines(file)) {
                String[] f = line.split("\t", -1);
                if (f.length < 3) continue;
                List<String> args = new ArrayList<>();
                for (int i = 3; i < f.length; i++) args.add(plain(f[i]));
                out.add(new Call(plain(f[0]), args, f[1], plain(f[2])));
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    /** The method of the old name: note the arguments, call the way in behind it, note what came back or was raised. */
    private static String front(String type, MethodDeclaration m) {
        String args = String.join(", ", m.getParameters().stream().map(p -> p.getNameAsString()).toList());
        String entry = "\"" + type + "." + m.getNameAsString() + "\"";
        String call = m.getNameAsString() + BEHIND + "(" + args + ")";
        String done = m.getType().isVoidType()
                ? call + "; " + RECORDED + ".done(" + entry + ", $in);"
                : m.getType().asString() + " $out = " + call + "; " + RECORDED + ".returned(" + entry + ", $in, $out); return $out;";
        return "{ String[] $in = " + RECORDED + ".enter(new Object[] {" + args + "}); try { " + done
                + " } catch (Throwable $t) { " + RECORDED + ".raised(" + entry + ", $in, $t); throw $t; } }";
    }

    private static String plain(String field) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c != '\\' || i + 1 == field.length()) {
                sb.append(c);
                continue;
            }
            char n = field.charAt(++i);
            sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
        }
        return sb.toString();
    }

    /** The recorder's source: plain Java with no dependency, so it builds wherever the project does. */
    private static String recorder(Path calls, String claimPrefix) {
        String dir = calls.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                package %s;

                public final class %s {
                    private static final String DIR = "%s";
                    private static final String PREFIX = "%s";
                    private static final int LONGEST = 300;
                    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
                        @Override protected int[] initialValue() { return new int[1]; }
                    };

                    private %s() {
                    }

                    /** The arguments as text when this is a call from outside, null when a way in called another. */
                    public static String[] enter(Object[] args) {
                        int[] depth = DEPTH.get();
                        if (depth[0]++ > 0) return null;
                        String[] in = new String[args.length];
                        for (int i = 0; i < args.length; i++) in[i] = show(args[i]);
                        return in;
                    }

                    public static void done(String entry, String[] in) {
                        leave(entry, in, "done", "");
                    }

                    public static void returned(String entry, String[] in, Object out) {
                        leave(entry, in, "returned", in == null ? "" : show(out));
                    }

                    public static void raised(String entry, String[] in, Throwable t) {
                        String message = null;
                        try {
                            message = in == null ? null : t.getMessage();
                        } catch (Throwable ignored) {
                            // a message that cannot be read is no message
                        }
                        leave(entry, in, "raised", t.getClass().getSimpleName() + (message == null ? "" : ": " + message));
                    }

                    private static void leave(String entry, String[] in, String how, String value) {
                        try {
                            if (in == null) return;
                            // leave <- done/returned/raised <- the way in <- whoever called it
                            StackTraceElement[] stack = new Throwable().getStackTrace();
                            if (stack.length < 4) return;
                            String caller = stack[3].getClassName();
                            caller = caller.substring(caller.lastIndexOf('.') + 1);
                            if (!caller.startsWith(PREFIX)) return;
                            int inner = caller.indexOf('$');
                            if (inner > 0) caller = caller.substring(0, inner);
                            StringBuilder line = new StringBuilder(field(entry)).append('\\t').append(how).append('\\t').append(field(value));
                            for (String a : in) line.append('\\t').append(field(a));
                            line.append('\\n');
                            synchronized (%s.class) {
                                java.nio.file.Files.write(java.nio.file.Paths.get(DIR, caller + "%s"),
                                        line.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                            }
                        } catch (Throwable ignored) {
                            // the record is beside the point of the call: it must never change what the call does
                        } finally {
                            DEPTH.get()[0]--;
                        }
                    }

                    private static String show(Object o) {
                        if (o == null) return "null";
                        try {
                            String s;
                            if (o instanceof CharSequence) s = "\\"" + o + "\\"";
                            else if (o instanceof Character) s = "'" + o + "'";
                            else if (o instanceof Object[]) s = java.util.Arrays.deepToString((Object[]) o);
                            else if (o.getClass().isArray()) {
                                StringBuilder sb = new StringBuilder("[");
                                for (int i = 0; i < java.lang.reflect.Array.getLength(o); i++) {
                                    sb.append(i == 0 ? "" : ", ").append(java.lang.reflect.Array.get(o, i));
                                }
                                s = sb.append(']').toString();
                            } else {
                                s = String.valueOf(o);
                                // an object that says nothing of itself is named by its type, not by where it lives
                                if (s.equals(o.getClass().getName() + "@" + Integer.toHexString(o.hashCode()))) {
                                    s = o.getClass().getSimpleName();
                                }
                            }
                            return s.length() > LONGEST ? s.substring(0, LONGEST) + " ..." : s;
                        } catch (Throwable t) {
                            return "<" + o.getClass().getSimpleName() + ">";
                        }
                    }

                    private static String field(String s) {
                        return s.replace("\\\\", "\\\\\\\\").replace("\\t", "\\\\t").replace("\\n", "\\\\n").replace("\\r", "\\\\r");
                    }
                }
                """.formatted(PACKAGE, TYPE, dir, claimPrefix, TYPE, TYPE, EXT);
    }
}
