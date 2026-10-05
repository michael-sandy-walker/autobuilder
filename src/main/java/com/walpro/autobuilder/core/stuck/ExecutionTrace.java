package com.walpro.autobuilder.core.stuck;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * T4-DY, the user's decision 2026-10-01 ("values too"): what a failing test's call into the project's own code
 * actually EXECUTED -- each call's arguments, what it returned, the line it returned at and the values in scope
 * there -- observed by re-running that one test under the JDK's debugger (JDI), in a copy of the tree.
 *
 * <p>parameterized-rows (2026-10-01): in three of five final trees the row logic was right and one lookup was
 * wrong -- {@code findClass} compared a simple name with the fully qualified name it was given -- and all the
 * loop heard was "expected: \"green | Nib | 4\" but was: null". The fact that ends that is where the empty came
 * from: {@code findClass(className = "org.sample.quill.InkTest") -> null, simpleName = "org.sample.quill.InkTest"}.
 *
 * <p>Only the project's own main classes are traced (by the classes its {@code src/main/java} declares), never
 * the test or the libraries; the run is bounded by {@link #CALL_LIMIT} calls and a time budget. A Maven project
 * only (it self-gates on {@code pom.xml}); anything else, or any failure to attach, is no answer, never a guess.
 */
@Slf4j
public final class ExecutionTrace {

    /** Most calls recorded per run: enough for one failing call's path, bounded for a loop that does not end. */
    static final int CALL_LIMIT = 400;
    /** How long past its deadline a trace's test JVM is let live before the watchdog ends it. */
    static final long WATCHDOG_GRACE_MS = 10_000;

    /** One call into the project's own code, as observed. */
    public record Call(int depth, String type, String method, List<String> arguments, String result, boolean threw,
                       int line, List<String> locals) {
    }

    private ExecutionTrace() {
    }

    /**
     * Re-runs {@code testClass#testMethod} in a copy of {@code project} under the debugger and returns the calls
     * into the classes {@code src/main/java} declares; empty when the project is not a Maven one, the run could not
     * be attached to, or nothing of the project's code was called.
     */
    public static Optional<List<Call>> trace(Path project, String testClass, String testMethod, long budgetMs) {
        if (project == null || !Files.exists(project.resolve("pom.xml"))) return Optional.empty();
        List<String> mainTypes = mainTypes(project);
        if (mainTypes.isEmpty()) return Optional.empty();
        Path copy = null;
        Process mvn = null;
        VirtualMachine vm = null;
        long deadline = System.currentTimeMillis() + budgetMs;
        try {
            copy = Files.createTempDirectory("execution-trace");
            copyTree(project, copy);
            int port;
            try (ServerSocket s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            String simpleTest = testClass.substring(testClass.lastIndexOf('.') + 1);
            mvn = new ProcessBuilder("mvn", "-o", "-q", "-B", "test", "-Dtest=" + simpleTest + "#" + testMethod,
                    "-Dsurefire.failIfNoSpecifiedTests=false", "-DfailIfNoTests=false", "-DforkCount=1", "-DreuseForks=false",
                    "-Dmaven.surefire.debug=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:" + port)
                    .directory(copy.toFile()).redirectErrorStream(true)
                    .redirectOutput(copy.resolve("trace-build.log").toFile()).start();
            // Nothing in a trace may outlast its budget: a remote call that never returns ends when its JVM does.
            Process watched = mvn;
            Thread watchdog = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(Math.max(1, deadline - System.currentTimeMillis()) + WATCHDOG_GRACE_MS);
                    watched.descendants().forEach(ProcessHandle::destroyForcibly);
                    watched.destroyForcibly();
                } catch (InterruptedException done) {
                    // the trace ended in time
                }
            });
            try {
                vm = attach(port, deadline, mvn);
                if (vm == null) {
                    // Say why: a test that does not build never opens the port (ladder-rows field33: a trace asked for
                    // while the tree did not compile ended in 7 s, logged only as "could not be attached to").
                    if (!mvn.isAlive()) log.info("T4-DY: execution trace of {}#{} not taken: the test did not build -- {}",
                            testClass, testMethod, firstError(copy.resolve("trace-build.log")));
                    return Optional.empty();
                }
                return Optional.of(record(vm, mainTypes, deadline)).filter(l -> !l.isEmpty());
            } finally {
                watchdog.interrupt();
            }
        } catch (Exception e) {
            log.info("T4-DY: execution trace of {}#{} not taken: {}", testClass, testMethod, e.getMessage());
            return Optional.empty();
        } finally {
            if (vm != null) {
                try {
                    vm.dispose();
                } catch (Exception ignored) {
                    // already gone
                }
            }
            if (mvn != null) {
                try {
                    if (!mvn.waitFor(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)) {
                        mvn.descendants().forEach(ProcessHandle::destroyForcibly);
                        mvn.destroyForcibly();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    mvn.destroyForcibly();
                }
            }
            if (copy != null) deleteQuietly(copy);
        }
    }

    /** The top-level types {@code src/main/java} declares, fully qualified, from the files' paths. */
    static List<String> mainTypes(Path project) {
        Path main = project.resolve("src/main/java");
        if (!Files.isDirectory(main)) return List.of();
        try (Stream<Path> files = Files.walk(main)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> main.relativize(p).toString().replace('\\', '/'))
                    .map(r -> r.substring(0, r.length() - ".java".length()).replace('/', '.'))
                    .filter(n -> !n.endsWith("package-info") && !n.endsWith("module-info"))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static VirtualMachine attach(int port, long deadline, Process mvn) throws InterruptedException {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElse(null);
        if (connector == null) return null;
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue("localhost");
        args.get("port").setValue(String.valueOf(port));
        while (System.currentTimeMillis() < deadline && mvn.isAlive()) {
            try {
                return connector.attach(args);
            } catch (Exception notYet) {
                Thread.sleep(500);   // the build compiles first; the test JVM opens the port when it forks
            }
        }
        return null;
    }

    private static List<Call> record(VirtualMachine vm, List<String> mainTypes, long deadline) throws InterruptedException {
        List<Call> calls = new ArrayList<>();
        try {
            recordInto(calls, vm, mainTypes, deadline);
        } catch (com.sun.jdi.VMDisconnectedException gone) {
            // the test JVM ended, or the watchdog ended it: what was recorded stands
        }
        return calls;
    }

    private static void recordInto(List<Call> calls, VirtualMachine vm, List<String> mainTypes, long deadline) throws InterruptedException {
        EventRequestManager erm = vm.eventRequestManager();
        List<EventRequest> requests = new ArrayList<>();
        // One entry and one exit request per root package, the project's own types picked in the handler: a request
        // per type (two filters each) stopped the trace from seeing any call once a tree had 20 types -- every method
        // the JVM runs is checked against every request, and a Mockito test runs a great many (ladder-rows field32:
        // a trace of TestRunGateTest saw no call in 250 s; two such requests saw the calls at once).
        for (String root : rootPackages(mainTypes)) {
            for (String filter : List.of(root + ".*")) {
                MethodEntryRequest entry = erm.createMethodEntryRequest();
                entry.addClassFilter(filter);
                MethodExitRequest exit = erm.createMethodExitRequest();
                exit.addClassFilter(filter);
                for (EventRequest r : List.of(entry, exit)) {
                    r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    r.enable();
                    requests.add(r);
                }
            }
        }
        // Every exception thrown while a thread is inside the project's code, wherever thrown: one a project method
        // CATCHES is part of that call's story even when a library threw it (parameterized-rows field24: JavaParser's
        // "Text Block Literals are not supported ... JAVA_15", caught by the method under test and returned as
        // empty). Only while inside: an exception caught in project code is thrown during a project call, and the
        // first version, which asked for every exception in the JVM, drowned in a mocking library's own (ladder-rows
        // field32: a Mockito test's trace ran out its budget before the test reached the project).
        Map<ThreadReference, ExceptionRequest> watching = new HashMap<>();
        vm.resume();
        Map<ThreadReference, Deque<List<String>>> pending = new HashMap<>();
        Map<ThreadReference, String> thrownBy = new HashMap<>();
        Map<ThreadReference, Map<com.sun.jdi.Method, String>> caughtBy = new HashMap<>();
        while (System.currentTimeMillis() < deadline) {
            EventSet set = vm.eventQueue().remove(Math.max(1, deadline - System.currentTimeMillis()));
            if (set == null) break;
            boolean over = false;
            for (Event event : set) {
                // One event that cannot be read must not stop the trace with the test thread still suspended: the
                // set would never be resumed, the test JVM would hang to the budget, and no call would be shown
                // (ladder-rows field32: "no trace -- the run could not be attached to", 250 s, nothing recorded).
                try {
                    if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) return;
                    if (event instanceof MethodEntryEvent e) {
                        if (skipped(e.method()) || !isProject(e.method().declaringType().name(), mainTypes)) continue;
                        Deque<List<String>> stack = pending.computeIfAbsent(e.thread(), t -> new ArrayDeque<>());
                        if (stack.isEmpty() && !watching.containsKey(e.thread())) {
                            ExceptionRequest inside = erm.createExceptionRequest(null, true, true);
                            inside.addThreadFilter(e.thread());
                            inside.setSuspendPolicy(EventRequest.SUSPEND_NONE);
                            inside.enable();
                            watching.put(e.thread(), inside);
                        }
                        stack.push(arguments(e.thread(), e.method(), requests));
                    } else if (event instanceof ExceptionEvent e) {
                        com.sun.jdi.Location caught = e.catchLocation();
                        if (caught != null && isProject(caught.declaringType().name(), mainTypes)) {
                            caughtBy.computeIfAbsent(e.thread(), t -> new HashMap<>()).put(caught.method(),
                                    "caught " + e.exception().referenceType().name() + " at line " + caught.lineNumber()
                                            + (isProject(e.location().declaringType().name(), mainTypes) ? ""
                                            : " (thrown in " + e.location().declaringType().name() + ")")
                                            + message(e.exception()));
                        } else if (isProject(e.location().declaringType().name(), mainTypes)) {
                            thrownBy.put(e.thread(), e.exception().referenceType().name() + " at line " + e.location().lineNumber()
                                    + message(e.exception()));
                        }
                    } else if (event instanceof MethodExitEvent e) {
                        if (skipped(e.method()) || !isProject(e.method().declaringType().name(), mainTypes)) continue;
                        Deque<List<String>> stack = pending.getOrDefault(e.thread(), new ArrayDeque<>());
                        List<String> args = stack.isEmpty() ? List.of() : stack.pop();
                        if (stack.isEmpty() && watching.containsKey(e.thread())) erm.deleteEventRequest(watching.remove(e.thread()));
                        String threw = thrownBy.remove(e.thread());
                        boolean isVoid = "void".equals(e.method().returnTypeName());
                        String caught = caughtBy.getOrDefault(e.thread(), Map.of()).isEmpty() ? null
                                : caughtBy.get(e.thread()).remove(e.method());
                        String result = threw != null ? "threw " + threw : isVoid ? "void" : value(e.returnValue(), 0, e.thread(), requests);
                        if (caught != null && threw == null) result = result + "  after it " + caught;
                        calls.add(new Call(stack.size(), e.method().declaringType().name(), e.method().name(), args, result,
                                threw != null, e.location().lineNumber(), locals(e.thread(), args.size(), requests)));
                        if (calls.size() >= CALL_LIMIT) over = true;
                    }
                } catch (com.sun.jdi.VMDisconnectedException gone) {
                    throw gone;
                } catch (RuntimeException unreadable) {
                    log.warn("T4-DY: execution trace: an event could not be read ({}) -- skipped", unreadable.toString());
                }
            }
            if (over) {
                requests.forEach(EventRequest::disable);
                set.resume();
                return;
            }
            set.resume();
        }
        return;
    }

    /** The packages the main types live under, cut to their common roots: com.a.b.X and com.a.c.Y give com.a. */
    static List<String> rootPackages(List<String> mainTypes) {
        java.util.TreeSet<String> pkgs = new java.util.TreeSet<>();
        for (String t : mainTypes) pkgs.add(t.contains(".") ? t.substring(0, t.lastIndexOf('.')) : t);
        List<String> roots = new ArrayList<>();
        for (String p : pkgs) {
            String[] parts = p.split("\\.");
            String root = parts.length >= 2 ? parts[0] + "." + parts[1] : p;   // e.g. com.walpro, org.sample
            if (!roots.contains(root)) roots.add(root);
        }
        return roots;
    }

    /** The first error line of a build log, or a short note when there is none. */
    private static String firstError(Path buildLog) {
        try (Stream<String> lines = Files.lines(buildLog)) {
            return lines.filter(l -> l.contains("[ERROR]") && l.contains(".java")).findFirst()
                    .map(l -> l.replaceAll("\\x1B\\[[0-9;]*m", "").strip()).orElse("no error line in the build log");
        } catch (IOException | java.io.UncheckedIOException e) {
            return "the build log could not be read";
        }
    }

    private static boolean isProject(String type, List<String> mainTypes) {
        if (type.contains("$$Lambda")) return false;   // the JVM's generated lambda classes: plumbing, not calls
        for (String t : mainTypes) if (type.equals(t) || type.startsWith(t + "$")) return true;
        return false;
    }

    /** An exception's own message, read from its field (no call into the test JVM), or nothing. */
    private static String message(ObjectReference exception) {
        try {
            Field f = exception.referenceType().fieldByName("detailMessage");
            if (f == null) {
                for (com.sun.jdi.ReferenceType t = exception.referenceType(); t instanceof com.sun.jdi.ClassType c && f == null; t = c.superclass()) {
                    f = t.fieldByName("detailMessage");
                }
            }
            if (f != null && exception.getValue(f) instanceof StringReference m) return ": " + crop(m.value().strip(), 300);
        } catch (Exception e) {
            // collected or unreadable: the type and line stand alone
        }
        return "";
    }

    private static List<String> arguments(ThreadReference thread, com.sun.jdi.Method method, List<EventRequest> quiet) {
        List<String> out = new ArrayList<>();
        try {
            StackFrame frame = thread.frame(0);
            List<Value> values = frame.getArgumentValues();
            List<String> names;
            try {
                names = method.arguments().stream().map(LocalVariable::name).toList();
            } catch (AbsentInformationException e) {
                names = List.of();
            }
            for (int i = 0; i < values.size(); i++) {
                out.add((i < names.size() ? names.get(i) + " = " : "") + value(values.get(i), 0, thread, quiet));
            }
        } catch (Exception e) {
            // a frame that cannot be read gives no arguments
        }
        return out;
    }

    /** The values in scope where the call returned, other than its arguments. */
    private static List<String> locals(ThreadReference thread, int argumentCount, List<EventRequest> quiet) {
        List<String> out = new ArrayList<>();
        try {
            StackFrame frame = thread.frame(0);
            for (LocalVariable v : frame.visibleVariables()) {
                if (v.isArgument()) continue;
                out.add(v.name() + " = " + value(frame.getValue(v), 0, thread, quiet));
                if (out.size() >= 6) break;
            }
        } catch (Exception e) {
            // no debug information, or the frame is gone: no locals
        }
        return out;
    }

    /** Class initialisers, synthetic methods and lambdas are plumbing, not calls a reader follows. */
    private static boolean skipped(com.sun.jdi.Method m) {
        return m.isSynthetic() || m.isStaticInitializer() || m.name().startsWith("lambda$");
    }

    /**
     * A value as a reader needs it: strings quoted; a JDK object by its own {@code toString()} (a path is its
     * path, not its fields -- invoked on the suspended thread); any other object by type and up to four fields.
     *
     * <p>The trace's own requests are off while {@code toString()} runs ({@code quiet}): a JDK collection holding
     * the project's objects calls THEIR {@code toString()}, a method entry the trace would suspend inside its own
     * invocation, which then never returns (receiver-questions run 2026-10-02_14-55-46 hung there 945 s).
     */
    static String value(Value v, int depth, ThreadReference thread, List<EventRequest> quiet) {
        if (v instanceof ObjectReference o && !(v instanceof StringReference) && !(v instanceof ArrayReference)
                && thread != null && isPlatform(o.referenceType().name())) {
            try {
                com.sun.jdi.Method toString = o.referenceType().methodsByName("toString", "()Ljava/lang/String;").get(0);
                quiet.forEach(EventRequest::disable);
                Value s;
                try {
                    s = o.invokeMethod(thread, toString, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
                } finally {
                    quiet.forEach(EventRequest::enable);
                }
                if (s instanceof StringReference str) return simple(o.referenceType().name()) + "(" + crop(str.value(), 120) + ")";
            } catch (Exception e) {
                // fall through to the fields
            }
        }
        return value(v, depth);
    }

    private static boolean isPlatform(String type) {
        return type.startsWith("java.") || type.startsWith("javax.") || type.startsWith("sun.") || type.startsWith("jdk.");
    }

    /** As {@link #value(Value, int, ThreadReference, List)}, by fields only. */
    static String value(Value v, int depth) {
        if (v == null) return "null";
        if (v instanceof StringReference s) return '"' + crop(s.value(), 120) + '"';
        if (v instanceof PrimitiveValue) return v.toString();
        if (v instanceof ArrayReference a) return simple(a.referenceType().name()) + "(length " + a.length() + ")";
        if (v instanceof ObjectReference o) {
            String type = simple(o.referenceType().name());
            if (depth > 0) return type;
            StringBuilder sb = new StringBuilder(type).append('{');
            int shown = 0;
            for (Field f : o.referenceType().allFields()) {
                if (f.isStatic()) continue;
                if (shown++ == 4) {
                    sb.append(", ...");
                    break;
                }
                sb.append(shown > 1 ? ", " : "").append(f.name()).append('=').append(value(o.getValue(f), depth + 1));
            }
            return sb.append('}').toString();
        }
        return crop(v.toString(), 120);
    }

    /**
     * The calls as an indented trace, each with what it returned, the line it returned at (its source text when the
     * sources are known) and the values in scope there; at most {@code maxLines} lines, the rest counted.
     */
    public static String render(List<Call> calls, Map<String, String> sources, int maxLines) {
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (Call c : calls) {
            if (lines >= maxLines) {
                sb.append("  ... and ").append(calls.size() - lines).append(" more call(s)\n");
                break;
            }
            String indent = "  ".repeat(c.depth() + 1);
            String text = lineText(sources, c.type(), c.line());
            sb.append(indent).append(simple(c.type())).append('.').append(c.method())
                    .append('(').append(String.join(", ", c.arguments())).append(") -> ").append(c.result())
                    .append("  [").append(c.threw() ? "at" : "returned at").append(" line ").append(c.line())
                    .append(text == null ? "" : ": " + text).append(']')
                    .append(c.locals().isEmpty() ? "" : "  where " + String.join(", ", c.locals())).append('\n');
            lines++;
        }
        return sb.toString().stripTrailing();
    }

    private static String lineText(Map<String, String> sources, String type, int line) {
        if (sources == null || line < 1) return null;
        String top = type.contains("$") ? type.substring(0, type.indexOf('$')) : type;
        String path = "src/main/java/" + top.replace('.', '/') + ".java";
        String src = sources.get(path);
        if (src == null) return null;
        String[] all = src.split("\\R", -1);
        return line <= all.length ? crop(all[line - 1].strip(), 120) : null;
    }

    private static String simple(String name) {
        String n = name.substring(name.lastIndexOf('.') + 1);
        return n.replace('$', '.');
    }

    private static String crop(String s, int max) {
        String one = s.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path rel = from.relativize(p);
                if (rel.toString().startsWith("target") || rel.toString().startsWith(".git")) continue;
                Path dest = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest);
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a leftover temp file is harmless
                }
            });
        } catch (IOException ignored) {
            // nothing to delete
        }
    }
}
