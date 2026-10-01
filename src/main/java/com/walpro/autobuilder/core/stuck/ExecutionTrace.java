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
            vm = attach(port, deadline, mvn);
            if (vm == null) return Optional.empty();
            return Optional.of(record(vm, mainTypes, deadline)).filter(l -> !l.isEmpty());
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
        EventRequestManager erm = vm.eventRequestManager();
        List<EventRequest> requests = new ArrayList<>();
        for (String type : mainTypes) {
            for (String filter : List.of(type, type + "$*")) {
                MethodEntryRequest entry = erm.createMethodEntryRequest();
                entry.addClassFilter(filter);
                MethodExitRequest exit = erm.createMethodExitRequest();
                exit.addClassFilter(filter);
                ExceptionRequest thrown = erm.createExceptionRequest(null, true, true);
                thrown.addClassFilter(filter);
                for (EventRequest r : List.of(entry, exit, thrown)) {
                    r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    r.enable();
                    requests.add(r);
                }
            }
        }
        vm.resume();
        List<Call> calls = new ArrayList<>();
        Map<ThreadReference, Deque<List<String>>> pending = new HashMap<>();
        Map<ThreadReference, String> thrownBy = new HashMap<>();
        while (System.currentTimeMillis() < deadline) {
            EventSet set = vm.eventQueue().remove(Math.max(1, deadline - System.currentTimeMillis()));
            if (set == null) break;
            boolean over = false;
            for (Event event : set) {
                if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) return calls;
                if (event instanceof MethodEntryEvent e) {
                    if (skipped(e.method())) continue;
                    pending.computeIfAbsent(e.thread(), t -> new ArrayDeque<>()).push(arguments(e.thread(), e.method()));
                } else if (event instanceof ExceptionEvent e) {
                    thrownBy.put(e.thread(), e.exception().referenceType().name() + " at line " + e.location().lineNumber());
                } else if (event instanceof MethodExitEvent e) {
                    if (skipped(e.method())) continue;
                    Deque<List<String>> stack = pending.getOrDefault(e.thread(), new ArrayDeque<>());
                    List<String> args = stack.isEmpty() ? List.of() : stack.pop();
                    String threw = thrownBy.remove(e.thread());
                    boolean isVoid = "void".equals(e.method().returnTypeName());
                    String result = threw != null ? "threw " + threw : isVoid ? "void" : value(e.returnValue(), 0, e.thread());
                    calls.add(new Call(stack.size(), e.method().declaringType().name(), e.method().name(), args, result,
                            threw != null, e.location().lineNumber(), locals(e.thread(), args.size())));
                    if (calls.size() >= CALL_LIMIT) over = true;
                }
            }
            if (over) {
                requests.forEach(EventRequest::disable);
                set.resume();
                return calls;
            }
            set.resume();
        }
        return calls;
    }

    private static List<String> arguments(ThreadReference thread, com.sun.jdi.Method method) {
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
                out.add((i < names.size() ? names.get(i) + " = " : "") + value(values.get(i), 0, thread));
            }
        } catch (Exception e) {
            // a frame that cannot be read gives no arguments
        }
        return out;
    }

    /** The values in scope where the call returned, other than its arguments. */
    private static List<String> locals(ThreadReference thread, int argumentCount) {
        List<String> out = new ArrayList<>();
        try {
            StackFrame frame = thread.frame(0);
            for (LocalVariable v : frame.visibleVariables()) {
                if (v.isArgument()) continue;
                out.add(v.name() + " = " + value(frame.getValue(v), 0, thread));
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
     */
    static String value(Value v, int depth, ThreadReference thread) {
        if (v instanceof ObjectReference o && !(v instanceof StringReference) && !(v instanceof ArrayReference)
                && thread != null && isPlatform(o.referenceType().name())) {
            try {
                com.sun.jdi.Method toString = o.referenceType().methodsByName("toString", "()Ljava/lang/String;").get(0);
                Value s = o.invokeMethod(thread, toString, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
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

    /** As {@link #value(Value, int, ThreadReference)}, by fields only. */
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
