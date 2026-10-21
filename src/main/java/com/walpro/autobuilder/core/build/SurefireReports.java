package com.walpro.autobuilder.core.build;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * T4-CQ: reads the test runner's per-class XML reports into a {@link TestRunReport}.
 *
 * <p><b>Only reports written by THIS run count.</b> The runner never deletes the report of a test
 * class that has since been removed, so a directory listing mixes this run with earlier ones. Each
 * file is judged by its own modification time against the moment the run started; older files are
 * someone else's evidence and are skipped.
 */
@Slf4j
public final class SurefireReports {

    private SurefireReports() {
    }

    /** Empty when no report of this run exists (the run failed before any test ran, or none exist). */
    public static Optional<TestRunReport> read(Path reportDir, long runStartedAtMillis) {
        return read(reportDir, runStartedAtMillis, null);
    }

    /**
     * As {@link #read(Path, long)}, and a failing parameterized invocation carries the row it ran with,
     * read from the test source under {@code projectRoot} (null: no rows are read).
     */
    public static Optional<TestRunReport> read(Path reportDir, long runStartedAtMillis, Path projectRoot) {
        if (reportDir == null || !Files.isDirectory(reportDir)) return Optional.empty();
        int run = 0, failures = 0, errors = 0, skipped = 0, files = 0;
        List<TestRunReport.Failure> failing = new ArrayList<>();
        Map<String, List<TestRunReport.Case>> byClass = new LinkedHashMap<>();
        List<Path> reports;
        try (Stream<Path> s = Files.list(reportDir)) {
            reports = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("TEST-") && n.endsWith(".xml");
            }).sorted().toList();
        } catch (Exception e) {
            return Optional.empty();
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // A parser without the feature still reads these reports; they carry no DOCTYPE.
        }
        for (Path report : reports) {
            try {
                if (Files.getLastModifiedTime(report).toMillis() < runStartedAtMillis) continue;
                Document doc;
                try (InputStream in = Files.newInputStream(report)) {
                    doc = f.newDocumentBuilder().parse(in);
                }
                files++;
                NodeList cases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element tc = (Element) cases.item(i);
                    String cls = tc.getAttribute("classname");
                    String method = tc.getAttribute("name");
                    TestRunReport.Outcome outcome = TestRunReport.Outcome.PASSED;
                    String message = null;
                    for (Node c = tc.getFirstChild(); c != null; c = c.getNextSibling()) {
                        if (!(c instanceof Element e)) continue;
                        switch (e.getTagName()) {
                            case "failure" -> { outcome = TestRunReport.Outcome.FAILED; message = message(e); }
                            case "error" -> { outcome = TestRunReport.Outcome.ERROR; message = message(e); }
                            case "skipped" -> outcome = TestRunReport.Outcome.SKIPPED;
                            default -> { }
                        }
                    }
                    run++;
                    switch (outcome) {
                        case FAILED -> failures++;
                        case ERROR -> errors++;
                        case SKIPPED -> skipped++;
                        default -> { }
                    }
                    byClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(new TestRunReport.Case(method, outcome));
                    if (outcome == TestRunReport.Outcome.FAILED || outcome == TestRunReport.Outcome.ERROR) {
                        String input = ParameterizedCaseInput.of(projectRoot, cls, method).orElse(null);
                        failing.add(new TestRunReport.Failure(cls, method, outcome, message, input));
                    }
                }
            } catch (Exception e) {
                log.debug("T4-CQ: unreadable test report {} ({}) -- skipped", report.getFileName(), e.toString());
            }
        }
        if (files == 0) return Optional.empty();
        return Optional.of(new TestRunReport(run, failures, errors, skipped, List.copyOf(failing), byClass));
    }

    /**
     * T4-EC: the reports of one run spread over several directories -- one per module of a multi-module build -- read
     * as one report. Empty when none of them holds a report of this run.
     */
    public static Optional<TestRunReport> readAll(List<Path> reportDirs, long runStartedAtMillis, Path projectRoot) {
        int run = 0, failures = 0, errors = 0, skipped = 0;
        boolean any = false;
        List<TestRunReport.Failure> failing = new ArrayList<>();
        Map<String, List<TestRunReport.Case>> byClass = new LinkedHashMap<>();
        for (Path dir : reportDirs) {
            Optional<TestRunReport> r = read(dir, runStartedAtMillis, projectRoot);
            if (r.isEmpty()) continue;
            any = true;
            run += r.get().run();
            failures += r.get().failures();
            errors += r.get().errors();
            skipped += r.get().skipped();
            failing.addAll(r.get().failing());
            r.get().byClass().forEach((cls, cases) -> byClass.computeIfAbsent(cls, k -> new ArrayList<>()).addAll(cases));
        }
        return any ? Optional.of(new TestRunReport(run, failures, errors, skipped, List.copyOf(failing), byClass)) : Optional.empty();
    }

    /**
     * The failure's message, every line of it joined onto one. It was the first line only, and an
     * assertion message's first line is often not the observation: AssertJ puts "expected: X" first and
     * "but was: Y" second, and a described assertion puts its description first -- receiver-questions run
     * 2026-09-26_21-17 relayed "expected: MEMBERS" and "[archived in ...]" for eleven failures and never
     * what the code returned, so the model grinded on a value it was never shown. The stack trace is not
     * part of the message attribute; when only the element's text is there, its trace lines are dropped.
     */
    static String message(Element e) {
        String m = e.getAttribute("message");
        boolean fromText = m == null || m.isBlank();
        if (fromText) m = e.getTextContent();
        if (m == null) return "";
        return String.join(" / ", m.strip().lines().map(String::strip).filter(l -> !l.isEmpty())
                .filter(l -> !(fromText && (l.startsWith("at ") || l.startsWith("... "))))
                .toList());
    }
}
