package com.walpro.autobuilder.core.build;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T6-T, skeleton first: the surface a specification's tests need, stubbed from the compiler's own errors until the
 * tests compile -- a class for every missing class, a method for every missing method, a constructor for every
 * missing constructor, each body throwing {@code UnsupportedOperationException("skeleton")}. No model is asked.
 *
 * <p>Why. A change that must touch several classes before anything compiles was the wall of the ticket ladder's
 * second band-3 case: ladder-grind field44 crossed it in none of three runs (1/51 each), later builds at minutes 20
 * to 78. Offline (scripts/skeleton_probe.py) the same specification compiled after four passes of this kind, about
 * 40 s, and the stub-only tree scored 41/51: what remained was a list of bodies, each checked by its own tests.
 *
 * <p>The return type of a new method starts as {@code Object} and is narrowed by what the tests do with the value:
 * "Object cannot be converted to T" makes it T; a test that asserts on it with a method the generic assert lacks
 * makes it the type whose assert has that method -- DISCOVERED on the project's own test classpath (the static
 * method wrapping the call is looked up beside the assert type, and the overload whose returned assert declares
 * the method names the value's type), never a table of one library's names.
 *
 * <p>Maven projects only, as the execution trace: it self-gates on {@code pom.xml}. Stubs are written only where
 * {@code writable} allows, so the operator's tests and protected files are never touched.
 */
@Slf4j
public final class SkeletonStubber {

    private SkeletonStubber() {
    }

    /** What a stub body says, so a stub can be told apart from written code. */
    public static final String STUB_MESSAGE = "skeleton";
    static final int MAX_PASSES = 12;

    public record Stub(String kind, String type, String member) {
        public String describe() {
            return kind.equals("class") ? "class " + type : type.substring(type.lastIndexOf('.') + 1) + "." + member;
        }
    }

    public record Result(boolean compiles, int passes, List<Stub> stubs, List<String> remaining,
                         List<SkeletonDeduction.Link> links, int deduced) {
        public Result(boolean compiles, int passes, List<Stub> stubs, List<String> remaining) {
            this(compiles, passes, stubs, remaining, List.of(), 0);
        }

        public static Result none(String why) {
            return new Result(false, 0, List.of(), List.of(why));
        }

        Result with(List<SkeletonDeduction.Link> links, int deduced) {
            return new Result(compiles, passes, stubs, remaining, links, deduced);
        }
    }

    /** One compiler error, with the symbol and location lines under it. */
    public record CompileError(String file, int line, String message, String symbolKind, String symbol, String locationKind,
                        String location, String found) {
    }

    private static final Pattern ERROR = Pattern.compile("^\\[ERROR\\] (\\S+\\.java):\\[(\\d+),(\\d+)\\] (.*)$");
    private static final Pattern SYMBOL = Pattern.compile("^(?:\\[ERROR\\])?\\s+symbol:\\s+(class|method|variable|constructor) (.+)$");
    private static final Pattern LOCATION = Pattern.compile(
            "^(?:\\[ERROR\\])?\\s+location:\\s+((?:class|interface|package|variable \\w+ of type) )?([\\w.$<>,]+)");
    private static final Pattern FOUND = Pattern.compile("^(?:\\[ERROR\\])?\\s+found:\\s+(.*)$");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

    /** The compiler's errors in a build's output, each once (the build tool prints them twice). */
    public static List<CompileError> errors(String output) {
        String[] lines = ANSI.matcher(output).replaceAll("").split("\\R");
        Map<String, CompileError> out = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ERROR.matcher(lines[i]);
            if (!m.matches()) continue;
            String kind = null, sym = null, locKind = null, loc = null, found = null;
            for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                if (ERROR.matcher(lines[j]).matches()) break;
                Matcher s = SYMBOL.matcher(lines[j]), l = LOCATION.matcher(lines[j]), f = FOUND.matcher(lines[j]);
                if (s.matches()) { kind = s.group(1); sym = s.group(2).strip(); }
                if (l.find()) { locKind = l.group(1) == null ? "" : l.group(1).strip(); loc = l.group(2); }
                if (f.matches()) found = f.group(1).strip();
            }
            CompileError e = new CompileError(m.group(1), Integer.parseInt(m.group(2)), m.group(4).strip(), kind, sym,
                    locKind, loc, found);
            out.putIfAbsent(e.file() + ":" + e.line() + ":" + e.message() + ":" + sym + ":" + loc, e);
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Stubs {@code project}'s missing surface until its tests compile or a pass adds nothing.
     *
     * @param writable    whether a source path (relative to the project) may be written
     * @param classpath   the project's test classpath, for discovering assertion types; may be blank
     */
    public static Result stub(Path project, Predicate<String> writable, String classpath, long timeoutMs) {
        if (!Files.isRegularFile(project.resolve("pom.xml"))) return Result.none("not a Maven project");
        Path main = project.resolve("src/main/java");
        List<Stub> stubs = new ArrayList<>();
        Map<String, String> returns = new LinkedHashMap<>();   // "fqn#method" -> current return type
        AssertionTypes assertions = new AssertionTypes(classpath);
        // the situational part: what the tests' syntax tree settles is written first; the compile loop below settles
        // what it could not (an ambiguous or unknown type stays Object for the loop to narrow)
        SkeletonDeduction.Result deduction;
        try {
            deduction = SkeletonDeduction.deduce(project, classpath);
        } catch (RuntimeException e) {
            log.debug("T6-T: no deduction, the compile loop alone: {}", e.getMessage());
            deduction = new SkeletonDeduction.Result(List.of(), List.of());
        }
        int deduced = writeDeduced(deduction.needs(), main, writable, stubs, returns, project);
        Result r = loop(project, main, writable, stubs, returns, assertions, timeoutMs);
        return r.with(deduction.links(), deduced);
    }

    private static int writeDeduced(List<SkeletonDeduction.Need> needs, Path main, Predicate<String> writable, List<Stub> stubs,
                                    Map<String, String> returns, Path project) {
        int n = 0;
        for (SkeletonDeduction.Need need : needs) if (need.kind().equals("class")) n += newClass(need.type(), main, writable, stubs, project);
        for (SkeletonDeduction.Need need : needs) {
            Path file = fileOf(main, need.type().replaceAll("<.*$", ""));
            if (!Files.isRegularFile(file)) continue;
            String params = String.join(", ", java.util.stream.IntStream.range(0, need.params().size())
                    .mapToObj(i -> need.params().get(i) + " a" + i).toList());
            if (need.kind().equals("constructor")) {
                String simple = SkeletonDeduction.simple(need.type());
                if (addMember(file, "public " + simple + "(" + params + ") { }", "public " + simple + "(" + params + ")", writable, project)) {
                    stubs.add(new Stub("constructor", need.type(), simple + "(" + String.join(",", need.params()) + ")"));
                    n++;
                }
            } else if (need.kind().equals("method")) {
                String ret = need.returns() == null ? "Object" : need.returns();
                String body = "{ throw new UnsupportedOperationException(\"" + STUB_MESSAGE + "\"); }";
                String member = "public " + (need.isStatic() ? "static " : "") + ret + " " + need.name() + "(" + params + ") " + body;
                if (addMember(file, member, " " + need.name() + "(", writable, project)) {
                    stubs.add(new Stub("method", need.type(), need.name() + "(" + String.join(",", need.params()) + ")"));
                    returns.put(need.type() + "#" + need.name(), ret);
                    n++;
                }
            }
        }
        return n;
    }

    private static Result loop(Path project, Path main, Predicate<String> writable, List<Stub> stubs, Map<String, String> returns,
                               AssertionTypes assertions, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<CompileError> errs = List.of();
        for (int pass = 1; pass <= MAX_PASSES && System.currentTimeMillis() < deadline; pass++) {
            Optional<String> out = compile(project, deadline - System.currentTimeMillis());
            if (out.isEmpty()) return new Result(true, pass, stubs, List.of());
            errs = errors(out.get());
            int added = 0;
            for (CompileError e : errs) added += apply(e, main, writable, stubs, returns, project);
            added += narrow(errs, main, returns, assertions, stubs);
            if (added == 0) {
                return new Result(false, pass, stubs, errs.stream().limit(10)
                        .map(e -> relative(project, e.file()) + ":" + e.line() + ": " + e.message()
                                + (e.symbol() == null ? "" : " (" + e.symbolKind() + " " + e.symbol() + ")")).toList());
            }
        }
        return new Result(false, MAX_PASSES, stubs, List.of("no compiling tree within the passes allowed"));
    }

    /** The test sources compiled; empty when they compiled, the output when they did not. */
    private static Optional<String> compile(Path project, long timeoutMs) {
        try {
            Process p = new ProcessBuilder("mvn", "-o", "-q", "-B", "test-compile").directory(project.toFile())
                    .redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return Optional.of("[ERROR] the compile did not finish");
            }
            return p.exitValue() == 0 ? Optional.empty() : Optional.of(new String(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.of("[ERROR] " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("[ERROR] interrupted");
        }
    }

    private static int apply(CompileError e, Path main, Predicate<String> writable, List<Stub> stubs,
                             Map<String, String> returns, Path project) {
        if (e.symbol() == null) return constructor(e, main, writable, stubs, project);
        String testPackage = packageOf(e.file());
        switch (e.symbolKind()) {
            case "variable" -> {
                // an unknown capitalised receiver is a class called statically
                String name = e.symbol();
                if (!name.isEmpty() && Character.isUpperCase(name.charAt(0)) && !name.equals(name.toUpperCase())
                        && testPackage != null) {
                    return newClass(testPackage + "." + name, main, writable, stubs, project);
                }
                return 0;
            }
            case "class" -> {
                String pkg = e.location() != null && "package".equals(e.locationKind()) ? e.location() : testPackage;
                return pkg == null ? 0 : newClass(pkg + "." + e.symbol(), main, writable, stubs, project);
            }
            case "method" -> {
                if (e.location() == null) return 0;
                String type = e.location().replaceAll("<.*$", "");
                Path file = fileOf(main, type);
                if (!Files.isRegularFile(file)) return 0;   // a library type: nothing of ours to stub
                String sig = e.symbol(), name = sig.substring(0, sig.indexOf('('));
                String callLine = lineOf(e.file(), e.line());
                String simple = type.substring(type.lastIndexOf('.') + 1);
                boolean isStatic = ("class".equals(e.locationKind()) || "interface".equals(e.locationKind()))
                        && Pattern.compile("\\b" + Pattern.quote(simple) + "\\s*\\.\\s*" + Pattern.quote(name) + "\\s*\\(")
                        .matcher(callLine).find();
                String key = type + "#" + name;
                String ret = returns.computeIfAbsent(key, k -> "Object");
                String member = "public " + (isStatic ? "static " : "") + ret + " " + name + "(" + params(sig)
                        + ") { throw new UnsupportedOperationException(\"" + STUB_MESSAGE + "\"); }";
                if (addMember(file, member, " " + name + "(" , writable, project)) {
                    stubs.add(new Stub("method", type, sig));
                    return 1;
                }
                return 0;
            }
            default -> {
                return 0;
            }
        }
    }

    private static int constructor(CompileError e, Path main, Predicate<String> writable, List<Stub> stubs, Path project) {
        Matcher m = Pattern.compile("constructor (\\w+) in class ([\\w.]+) cannot be applied").matcher(e.message());
        if (!m.find() || e.found() == null) return 0;
        Path file = fileOf(main, m.group(2));
        if (!Files.isRegularFile(file)) return 0;
        String args = "no arguments".equals(e.found()) ? "" : e.found();
        String member = "public " + m.group(1) + "(" + params(m.group(1) + "(" + args + ")") + ") { }";
        if (addMember(file, member, "public " + m.group(1) + "(" + params(m.group(1) + "(" + args + ")") + ")", writable, project)) {
            stubs.add(new Stub("constructor", m.group(2), m.group(1) + "(" + args + ")"));
            return 1;
        }
        return 0;
    }

    /** Narrows {@code Object} returns by what the tests do with the value. */
    private static int narrow(List<CompileError> errs, Path main, Map<String, String> returns, AssertionTypes assertions,
                              List<Stub> stubs) {
        int changed = 0;
        for (CompileError e : errs) {
            String want = null;
            Matcher conv = Pattern.compile("incompatible types: (?:java\\.lang\\.)?Object cannot be converted to (.+)$")
                    .matcher(e.message());
            if (conv.find()) want = conv.group(1).strip();
            String wrapper = null;
            String onlyMember = null;
            if (want == null && "method".equals(e.symbolKind()) && e.location() != null && !Files.isRegularFile(
                    fileOf(main, e.location().replaceAll("<.*$", "")))) {
                // a method missing on a LIBRARY type that wraps one of our values: the value's type is the one whose
                // wrapper returns a type that has this method
                String sig = e.symbol();
                String method = sig.substring(0, sig.indexOf('('));
                List<String> args = argTypes(sig);
                String line = lineOf(e.file(), e.line());
                Matcher w = Pattern.compile("(\\w+)\\s*\\(\\s*[\\w.()\\s]*?\\.\\s*(\\w+)\\s*\\([^()]*\\)\\s*\\)\\s*\\.\\s*"
                        + Pattern.quote(method) + "\\s*\\(").matcher(line);
                if (w.find()) {
                    wrapper = w.group(1);
                    List<String> kinds = assertions.valueTypes(e.location().replaceAll("<.*$", ""), wrapper, method, args);
                    want = kinds.isEmpty() ? null : kinds.get(0);
                    onlyMember = w.group(2);   // the stub INSIDE the assertion, never another one on the line
                }
            }
            if (want == null) continue;
            String line = lineOf(e.file(), e.line());
            for (Map.Entry<String, String> r : returns.entrySet()) {
                if (!"Object".equals(r.getValue())) continue;
                String type = r.getKey().substring(0, r.getKey().indexOf('#')), name = r.getKey().substring(r.getKey().indexOf('#') + 1);
                if (onlyMember != null && !onlyMember.equals(name)) continue;
                if (!Pattern.compile("\\.\\s*" + Pattern.quote(name) + "\\s*\\(").matcher(line).find()) continue;
                Path file = fileOf(main, type);
                try {
                    String src = Files.readString(file);
                    String next = src.replaceFirst("public (static )?Object " + Pattern.quote(name) + "\\(",
                            Matcher.quoteReplacement("public ") + "$1" + Matcher.quoteReplacement(want + " " + name + "("));
                    if (!next.equals(src)) {
                        Files.writeString(file, next);
                        r.setValue(want);
                        stubs.add(new Stub("return", type, name + " -> " + want));
                        changed++;
                    }
                } catch (IOException ex) {
                    log.debug("T6-T: could not narrow {}.{}: {}", type, name, ex.getMessage());
                }
            }
        }
        return changed;
    }

    private static int newClass(String fqn, Path main, Predicate<String> writable, List<Stub> stubs, Path project) {
        Path file = fileOf(main, fqn);
        if (Files.exists(file) || !writable.test(relative(project, file.toString()))) return 0;
        String pkg = fqn.substring(0, fqn.lastIndexOf('.')), name = fqn.substring(fqn.lastIndexOf('.') + 1);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package " + pkg + ";\n\n/** A skeleton: written from what the tests need; its bodies are"
                    + " still to be written. */\npublic class " + name + " {\n}\n");
            stubs.add(new Stub("class", fqn, null));
            return 1;
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean addMember(Path file, String member, String key, Predicate<String> writable, Path project) {
        if (!writable.test(relative(project, file.toString()))) return false;
        try {
            String src = Files.readString(file);
            int end = src.stripTrailing().lastIndexOf('}');
            if (end < 0) return false;
            // only the file's own top-level type; a same-named member already there is not ours to add
            if (src.contains(key)) return false;
            boolean isInterface = Pattern.compile("\\binterface\\s+" + Pattern.quote(simpleName(file)) + "\\b").matcher(src).find();
            String text = isInterface ? member.replaceFirst("^public (?!static)", "default ") : member;
            Files.writeString(file, src.substring(0, end) + "\n    " + text + "\n" + src.substring(end));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static String params(String sig) {
        List<String> types = argTypes(sig);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) out.add(types.get(i) + " a" + i);
        return String.join(", ", out);
    }

    static List<String> argTypes(String sig) {
        int open = sig.indexOf('('), close = sig.lastIndexOf(')');
        if (open < 0 || close <= open) return List.of();
        String inner = sig.substring(open + 1, close);
        List<String> types = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i <= inner.length(); i++) {
            char c = i < inner.length() ? inner.charAt(i) : ',';
            if (c == '<') depth++;
            if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                String t = inner.substring(start, i).strip();
                if (!t.isEmpty()) types.add(t.startsWith("<") ? "Object" : t);
                start = i + 1;
            }
        }
        return types;
    }

    private static Path fileOf(Path main, String fqn) {
        return main.resolve(fqn.replace('.', '/') + ".java");
    }

    private static String simpleName(Path file) {
        String n = file.getFileName().toString();
        return n.substring(0, n.length() - ".java".length());
    }

    private static String packageOf(String javaFile) {
        try {
            Matcher m = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE).matcher(Files.readString(Path.of(javaFile)));
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String lineOf(String javaFile, int line) {
        try {
            List<String> lines = Files.readAllLines(Path.of(javaFile));
            return line >= 1 && line <= lines.size() ? lines.get(line - 1) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String relative(Path project, String file) {
        Path p = Path.of(file);
        return (p.isAbsolute() && p.startsWith(project) ? project.relativize(p) : p).toString().replace('\\', '/');
    }

    /**
     * Which value type a library's wrapper must be given so that what it returns has a method -- read from the
     * project's own test classpath. The wrapper is looked up as a public static method of the classes in the
     * returned type's package; of its one-argument overloads, those whose return type declares {@code method} with
     * {@code arity} parameters name the candidate value types; a JDK interface is preferred to a JDK class, then
     * to anything else, and the type's parameters are filled from the arguments the test passed.
     */
    static final class AssertionTypes {
        private final URLClassLoader loader;
        private final List<Path> jars;

        AssertionTypes(String classpath) {
            List<URL> urls = new ArrayList<>();
            List<Path> js = new ArrayList<>();
            if (classpath != null) {
                for (String part : classpath.split(java.io.File.pathSeparator)) {
                    if (part.isBlank()) continue;
                    try {
                        urls.add(Path.of(part).toUri().toURL());
                        if (part.endsWith(".jar")) js.add(Path.of(part));
                    } catch (Exception ignored) {
                        // an unreadable entry is no evidence
                    }
                }
            }
            this.loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
            this.jars = js;
        }

        Optional<String> valueType(String returnedType, String wrapper, String method, int arity, List<String> args) {
            List<String> all = valueTypes(returnedType, wrapper, method, args);
            return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
        }

        /** Every kind of value whose wrapper returns a type with {@code method} taking {@code args}, most specific first. */
        List<String> valueTypes(String typeInPackage, String wrapper, String method, List<String> args) {
            String returnedType = typeInPackage;
            String pkg = returnedType.contains(".") ? returnedType.substring(0, returnedType.lastIndexOf('.')) : "";
            Set<Class<?>> candidates = new LinkedHashSet<>();
            for (String cls : classesIn(pkg)) {
                try {
                    Class<?> c = Class.forName(cls, false, loader);
                    for (Method m : c.getMethods()) {
                        if (!m.getName().equals(wrapper) || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1) continue;
                        boolean has = Arrays.stream(m.getReturnType().getMethods())
                                .anyMatch(r -> r.getName().equals(method) && accepts(r, args));
                        if (has) candidates.add(m.getParameterTypes()[0]);
                    }
                } catch (Throwable ignored) {
                    // a class that does not load says nothing
                }
            }
            // the most specific first: a List is also a Collection and an Iterable, and the test's value is the List
            List<Class<?>> all = new ArrayList<>(candidates);
            return all.stream()
                    .sorted(java.util.Comparator.<Class<?>>comparingInt(AssertionTypes::preference)
                            .thenComparing(t -> -all.stream().filter(o -> o != t && o.isAssignableFrom(t)).count())
                            .thenComparing(Class::getName))
                    .filter(t -> preference(t) < 3)
                    .map(t -> generic(t, args)).toList();
        }

        /** Whether a method can take arguments of these types (by erasure; a type it cannot load is taken to fit). */
        private boolean accepts(Method m, List<String> args) {
            Class<?>[] params = m.getParameterTypes();
            if (!m.isVarArgs() && params.length != args.size()) return false;
            if (m.isVarArgs() && args.size() < params.length - 1) return false;
            for (int i = 0; i < args.size(); i++) {
                Class<?> p = m.isVarArgs() && i >= params.length - 1 ? params[params.length - 1].getComponentType() : params[i];
                Class<?> a = load(args.get(i));
                if (a == null) continue;
                if (!wrap(p).isAssignableFrom(wrap(a))) return false;
            }
            return true;
        }

        private Class<?> load(String type) {
            String raw = type.replaceAll("<.*$", "");
            switch (raw) {
                case "int": return int.class;
                case "long": return long.class;
                case "boolean": return boolean.class;
                case "double": return double.class;
                case "char": return char.class;
                case "float": return float.class;
                case "short": return short.class;
                case "byte": return byte.class;
                default:
                    try {
                        return Class.forName(raw.contains(".") ? raw : "java.lang." + raw, false, loader);
                    } catch (Throwable e) {
                        return null;
                    }
            }
        }

        private static Class<?> wrap(Class<?> c) {
            if (!c.isPrimitive()) return c;
            if (c == int.class) return Integer.class;
            if (c == long.class) return Long.class;
            if (c == boolean.class) return Boolean.class;
            if (c == double.class) return Double.class;
            if (c == char.class) return Character.class;
            if (c == float.class) return Float.class;
            if (c == short.class) return Short.class;
            return Byte.class;
        }

        private static int preference(Class<?> t) {
            if (t.isPrimitive() || t.isArray()) return 3;
            String n = t.getName();
            if (n.startsWith("java.") && t.isInterface()) return 0;
            if (n.startsWith("java.")) return 1;
            return 2;
        }

        private static String generic(Class<?> t, List<String> args) {
            int params = t.getTypeParameters().length;
            if (params == 0) return t.getCanonicalName();
            List<String> boxed = new ArrayList<>();
            for (int i = 0; i < params; i++) boxed.add(i < args.size() ? box(args.get(i)) : "Object");
            return t.getCanonicalName() + "<" + String.join(", ", boxed) + ">";
        }

        private static String box(String t) {
            return switch (t) {
                case "int" -> "Integer";
                case "long" -> "Long";
                case "boolean" -> "Boolean";
                case "double" -> "Double";
                case "char" -> "Character";
                case "float" -> "Float";
                case "short" -> "Short";
                case "byte" -> "Byte";
                default -> t.replace("java.lang.", "");
            };
        }

        private List<String> classesIn(String pkg) {
            List<String> out = new ArrayList<>();
            String dir = pkg.replace('.', '/') + "/";
            for (Path jar : jars) {
                try (java.util.jar.JarFile j = new java.util.jar.JarFile(jar.toFile())) {
                    j.stream().map(java.util.jar.JarEntry::getName)
                            .filter(n -> n.startsWith(dir) && n.endsWith(".class") && n.indexOf('/', dir.length()) < 0 && !n.contains("$"))
                            .forEach(n -> out.add(n.substring(0, n.length() - ".class".length()).replace('/', '.')));
                } catch (IOException ignored) {
                    // an unreadable jar says nothing
                }
            }
            return out;
        }
    }
}
