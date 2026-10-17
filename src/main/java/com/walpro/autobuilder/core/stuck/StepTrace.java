package com.walpro.autobuilder.core.stuck;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;
import com.sun.jdi.request.StepRequest;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * T6-T: a method whose empty, false or null answer decided a failing test, walked line by line -- each line it ran and
 * the values that line changed.
 *
 * <p><b>The gap, from the field.</b> A trace shows what each call returned and the line it returned at, and the
 * reader still has to guess WHY. Ladder-rows runs 2026-10-15_00-29-38 and _02-32-05 traced check 15 seven times:
 * {@code SurefireReports.read(...) -> Optional.empty [returned at line 108: if (files == 0) ...]}, and the model went on
 * changing the wrong place for 70 minutes. The reason was two lines up: the method collects only files named
 * {@code TEST-*.xml}, the test wrote a {@code .txt} report, and {@code reports} was an empty list. Ladder-grind
 * 2026-10-14_00-22-30, check 17: {@code isInAnonymousClass -> false, where parent = null}; the fact was in the loop --
 * the first node above the method was the {@code new} expression itself, never the class declaration the code waited
 * for. Both are conditions inside a method the trace showed only the outcome of.
 *
 * <p><b>Which methods.</b> The project methods that answered nothing within the test's last top-level call, distinct,
 * the latest returned first -- the answers the failure turned on -- at most {@link #METHODS}. Each is walked at most
 * {@link #WALKS_PER_METHOD} times, keeping only invocations that again answer nothing by a path through the method not
 * walked yet (check 17's first two invocations took the same path, and the one the failure turned on took another),
 * each cut at {@link #STEP_LIMIT}
 * lines. Calls it makes are stepped over, not into: what each call returned shows in the values the line changed.
 */
@Slf4j
public final class StepTrace {

    static final int METHODS = 3;
    static final int WALKS_PER_METHOD = 2;
    static final int STEP_LIMIT = 40;

    /** One line run, the method it belongs to, how deep under the walked call (0), what it changed. */
    public record Step(int depth, String method, int line, List<String> changed) {
    }

    /** One invocation walked: the call, what it returned, its lines; {@code cut} when it ran past the limit. */
    public record Walk(String type, String method, List<String> arguments, String result, List<Step> steps, boolean cut) {
    }

    private StepTrace() {
    }

    /** Whether a rendered result is an answer of nothing: null, false, an empty optional, collection or string. */
    static boolean nothing(String result) {
        if (result == null) return false;
        String r = result.strip();
        return r.equals("null") || r.equals("false") || r.equals("\"\"") || r.startsWith("Optional(Optional.empty")
                || r.endsWith("([])") || r.endsWith("({})");
    }

    /** The methods to walk, as {type, method}, from a recorded trace. */
    public static List<String[]> decidingMethods(List<ExecutionTrace.Call> calls) {
        if (calls == null || calls.isEmpty()) return List.of();
        int top = calls.stream().mapToInt(ExecutionTrace.Call::depth).min().orElse(0);
        int last = -1;
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (calls.get(i).depth() == top) {
                last = i;
                break;
            }
        }
        int from = 0;
        for (int i = last - 1; i >= 0; i--) {
            if (calls.get(i).depth() == top) {
                from = i + 1;
                break;
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> out = new ArrayList<>();
        // Latest returned first: the answers nearest the failure. Ladder-rows run 2026-10-17_13-08-25: in return order
        // the first three were on paths the code no longer took to its answer, and the fourth -- the lookup that
        // returned empty for the method name it was given -- was left out of the walk.
        for (int i = last; i >= from && out.size() < METHODS; i--) {
            ExecutionTrace.Call c = calls.get(i);
            if (c.method().startsWith("<") || !nothing(c.result())) continue;
            if (seen.add(c.type() + "#" + c.method())) out.add(new String[]{c.type(), c.method()});
        }
        return out;
    }

    /** Re-runs the test under the debugger and walks the {@code targets}; empty when it could not be run. */
    public static Optional<List<Walk>> walk(Path project, String testClass, String testMethod, List<String[]> targets,
                                            long budgetMs) {
        if (targets == null || targets.isEmpty()) return Optional.empty();
        return ExecutionTrace.underDebugger(project, testClass, testMethod, budgetMs, (vm, deadline) -> {
            List<Walk> walks = new ArrayList<>();
            try {
                walkInto(walks, vm, targets, deadline);
            } catch (com.sun.jdi.VMDisconnectedException gone) {
                // the test JVM ended: what was walked stands
            }
            return walks;
        }).filter(w -> !w.isEmpty());
    }

    private static final class Open {
        String type;
        String method;
        List<String> arguments;
        int depth;
        StepRequest step;
        final List<Step> steps = new ArrayList<>();
        Map<String, String> locals = Map.of();
        boolean cut;
        /** Stepping again after a target it called was walked: the first step lands on the line that made the call. */
        boolean resumed;
    }

    /** Invocations looked at per method, at most: the ones that take a path not walked yet are what is kept. */
    static final int EXAMINED_PER_METHOD = 12;

    private static void walkInto(List<Walk> walks, VirtualMachine vm, List<String[]> targets, long deadline)
            throws InterruptedException {
        EventRequestManager erm = vm.eventRequestManager();
        List<EventRequest> requests = new ArrayList<>();
        Set<String> types = new LinkedHashSet<>();
        for (String[] t : targets) types.add(t[0]);
        for (String type : types) {
            MethodEntryRequest entry = erm.createMethodEntryRequest();
            entry.addClassFilter(type);
            MethodExitRequest exit = erm.createMethodExitRequest();
            exit.addClassFilter(type);
            for (EventRequest r : List.of(entry, exit)) {
                r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                r.enable();
                requests.add(r);
            }
        }
        Map<String, Integer> kept = new LinkedHashMap<>();
        Map<String, Integer> examined = new HashMap<>();
        Map<String, Set<List<Integer>>> paths = new HashMap<>();
        for (String[] t : targets) kept.put(t[0] + "#" + t[1], 0);
        // A walk per invocation, innermost last: the JVM allows one step request per thread, so the walk of the
        // caller pauses while a target it calls is walked, and resumes when that returns.
        Map<ThreadReference, java.util.Deque<Open>> open = new HashMap<>();
        vm.resume();
        while (System.currentTimeMillis() < deadline && kept.values().stream().anyMatch(n -> n < WALKS_PER_METHOD)) {
            EventSet set = vm.eventQueue().remove(Math.max(1, deadline - System.currentTimeMillis()));
            if (set == null) break;
            for (Event event : set) {
                try {
                    if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) return;
                    if (event instanceof MethodEntryEvent e) {
                        String key = e.method().declaringType().name() + "#" + e.method().name();
                        Integer n = kept.get(key);
                        if (n == null || n >= WALKS_PER_METHOD || examined.getOrDefault(key, 0) >= EXAMINED_PER_METHOD) continue;
                        examined.merge(key, 1, Integer::sum);
                        java.util.Deque<Open> stack = open.computeIfAbsent(e.thread(), t -> new java.util.ArrayDeque<>());
                        if (!stack.isEmpty() && stack.peek().step != null) {
                            erm.deleteEventRequest(stack.peek().step);
                            requests.remove(stack.peek().step);
                            stack.peek().step = null;
                        }
                        Open o = new Open();
                        o.type = e.method().declaringType().name();
                        o.method = e.method().name();
                        o.arguments = ExecutionTrace.arguments(e.thread(), e.method(), requests);
                        o.depth = e.thread().frameCount();
                        o.locals = locals(e.thread(), requests);
                        o.steps.add(new Step(0, o.method, e.location().lineNumber(), List.of()));
                        o.step = stepOver(erm, e.thread(), o.type, requests);
                        stack.push(o);
                    } else if (event instanceof StepEvent e) {
                        java.util.Deque<Open> stack = open.get(e.thread());
                        Open o = stack == null ? null : stack.peek();
                        if (o == null || o.cut || e.thread().frameCount() != o.depth) continue;
                        Map<String, String> now = locals(e.thread(), requests);
                        List<String> changed = new ArrayList<>();
                        Map<String, String> before = o.locals;
                        now.forEach((k, v) -> {
                            if (!v.equals(before.get(k))) changed.add(k + " = " + v);
                        });
                        o.locals = now;
                        // What a line changed is seen when the next line is reached: it belongs to the line before.
                        if (!changed.isEmpty() && !o.steps.isEmpty()) {
                            Step prev = o.steps.get(o.steps.size() - 1);
                            List<String> merged = new ArrayList<>(prev.changed());
                            merged.addAll(changed);
                            o.steps.set(o.steps.size() - 1, new Step(prev.depth(), prev.method(), prev.line(), merged));
                        }
                        boolean again = o.resumed && e.location().lineNumber() == o.steps.get(o.steps.size() - 1).line();
                        o.resumed = false;
                        if (again) continue;
                        if (o.steps.size() >= STEP_LIMIT) {
                            o.cut = true;
                            continue;
                        }
                        o.steps.add(new Step(0, o.method, e.location().lineNumber(), List.of()));
                    } else if (event instanceof MethodExitEvent e) {
                        java.util.Deque<Open> stack = open.get(e.thread());
                        Open o = stack == null ? null : stack.peek();
                        if (o == null || !e.method().name().equals(o.method)
                                || !e.method().declaringType().name().equals(o.type)
                                || e.thread().frameCount() != o.depth) continue;
                        stack.pop();
                        if (o.step != null) {
                            erm.deleteEventRequest(o.step);
                            requests.remove(o.step);
                        }
                        if (!stack.isEmpty() && !stack.peek().cut) {
                            stack.peek().step = stepOver(erm, e.thread(), stack.peek().type, requests);
                            stack.peek().resumed = true;
                        }
                        String result = "void".equals(e.method().returnTypeName()) ? "void"
                                : ExecutionTrace.value(e.returnValue(), 0, e.thread(), requests);
                        if (!nothing(result)) continue;
                        String key = o.type + "#" + o.method;
                        List<Integer> path = o.steps.stream().map(Step::line).toList();
                        if (!paths.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(path)) continue;   // a path already walked
                        if (kept.getOrDefault(key, 0) >= WALKS_PER_METHOD) continue;
                        walks.add(new Walk(o.type, o.method, o.arguments, result, List.copyOf(o.steps), o.cut));
                        kept.merge(key, 1, Integer::sum);
                    }
                } catch (com.sun.jdi.VMDisconnectedException gone) {
                    throw gone;
                } catch (RuntimeException | com.sun.jdi.IncompatibleThreadStateException unreadable) {
                    log.debug("T6-T: step trace: an event could not be read ({}) -- skipped", unreadable.toString());
                }
            }
            set.resume();
        }
        requests.forEach(r -> {
            try {
                r.disable();
            } catch (RuntimeException ignored) {
                // the JVM is gone
            }
        });
    }

    /**
     * A line step over calls, not into them: a step into a library (a parser, a stream) single-steps the JVM through
     * all of it, and the walk is about this method's own conditions.
     */
    private static StepRequest stepOver(EventRequestManager erm, ThreadReference thread, String type, List<EventRequest> requests) {
        StepRequest step = erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
        step.addClassFilter(type);
        step.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        step.enable();
        requests.add(step);
        return step;
    }

    /** The locals of the top frame, by name, as values a reader can read. */
    private static Map<String, String> locals(ThreadReference thread, List<EventRequest> quiet) {
        Map<String, String> out = new LinkedHashMap<>();
        ExecutionTrace.frameValues(thread, quiet).forEach((v, text) -> out.put(v.name(), text));
        return out;
    }

    /** The walks as text, the source of each line beside it, at most {@code maxLines} lines. */
    public static String render(List<Walk> walks, Map<String, String> sources, int maxLines) {
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (Walk w : walks) {
            if (lines >= maxLines) break;
            String type = ExecutionTrace.simple(w.type());
            sb.append("  Inside ").append(type).append('.').append(w.method()).append('(')
                    .append(String.join(", ", w.arguments())).append(") -> ").append(w.result())
                    .append(", line by line -- each line it ran, and what that line changed:\n");
            lines++;
            for (int i = 0; i < w.steps().size(); i++) {
                Step s = w.steps().get(i);
                if (lines >= maxLines) {
                    sb.append("    ... (more lines not shown)\n");
                    break;
                }
                String text = ExecutionTrace.lineText(sources, w.type(), s.line());
                if (text != null && text.contains("->") && text.endsWith("{") && i + 1 < w.steps().size()) {
                    text = withLambdaBody(text, sources, w.type(), s.line(), w.steps().get(i + 1).line());
                }
                sb.append("    ").append("  ".repeat(s.depth()))
                        .append(s.method().equals(w.method()) ? "" : "[" + s.method() + "] ")
                        .append("line ").append(s.line()).append(text == null ? "" : ": " + text)
                        .append(s.changed().isEmpty() ? "" : "   then " + String.join(", ", s.changed())).append('\n');
                lines++;
            }
            if (w.cut()) {
                sb.append("    ... it ran on past ").append(STEP_LIMIT).append(" lines\n");
                lines++;
            }
        }
        return sb.toString().stripTrailing();
    }

    /** Most lines of a lambda body shown beside the line that opens it. */
    static final int LAMBDA_BODY_LINES = 6;

    /**
     * A line that opens a lambda block, with the body's lines up to the {@code next} line the walk ran: the body runs in
     * a method of its own, stepped over, and its condition is often the one that decided the answer -- check 15's
     * {@code reports} came out empty from a filter whose test of the file name was on the two lines after the one shown.
     */
    private static String withLambdaBody(String text, Map<String, String> sources, String type, int line, int next) {
        if (next <= line + 1 || next - line - 1 > LAMBDA_BODY_LINES) return text;
        StringBuilder out = new StringBuilder(text);
        for (int l = line + 1; l < next; l++) {
            String body = ExecutionTrace.lineText(sources, type, l);
            if (body != null && !body.isEmpty()) out.append(' ').append(body);
        }
        return out.toString();
    }
}
