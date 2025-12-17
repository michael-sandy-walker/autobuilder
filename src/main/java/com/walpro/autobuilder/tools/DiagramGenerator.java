//package com.walpro.autobuilder.tools;
//
//import io.github.classgraph.ClassGraph;
//import io.github.classgraph.ClassInfo;
//import io.github.classgraph.ClassInfoList;
//import io.github.classgraph.ScanResult;
//
//import java.io.IOException;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.HashSet;
//import java.util.Set;
//
///**
// * Simple class-level PlantUML generator for Autobuilder.
// * Scans classes under com.walpro.autobuilder and emits a class diagram
// * grouped by package.
// */
//public class DiagramGenerator {
//
//    public static void main(String[] args) throws IOException {
//        String basePackage = "com.walpro.autobuilder";
//        Path out = Path.of("diagrams/autobuilder-classes.puml");
//
//        Files.createDirectories(out.getParent());
//
//        StringBuilder sb = new StringBuilder();
//        sb.append("@startuml\n");
//        sb.append("set namespaceSeparator .\n");
//        sb.append("skinparam packageStyle rectangle\n\n");
//
//        try (ScanResult scan = new ClassGraph()
//                .enableAllInfo()
//                .acceptPackages(basePackage)
//                .scan()) {
//
//            ClassInfoList allClasses = scan.getAllClasses();
//
//            // We use sets to avoid duplicate relation lines
//            Set<String> lines = new HashSet<>();
//
//            // Declare classes
//            for (ClassInfo ci : allClasses) {
//                if (ci.isAnnotation() || ci.isInterface()) {
//                    continue;
//                }
//                String name = ci.getName();
//                sb.append("class \"").append(name).append("\" {\n}\n");
//            }
//            sb.append("\n");
//
//            // Inheritance and interfaces
//            for (ClassInfo ci : allClasses) {
//                String name = ci.getName();
//
//                if (ci.getSuperclass() != null) {
//                    String superName = ci.getSuperclass().getName();
//                    if (superName.startsWith(basePackage)) {
//                        lines.add("\"" + superName + "\" <|-- \"" + name + "\"");
//                    }
//                }
//
//                ci.getInterfaces().forEach(iface -> {
//                    String ifaceName = iface.getName();
//                    if (ifaceName.startsWith(basePackage)) {
//                        lines.add("\"" + ifaceName + "\" <|.. \"" + name + "\"");
//                    }
//                });
//            }
//
//            // Field-based associations (simple version)
//            for (ClassInfo ci : allClasses) {
//                String from = ci.getName();
//                ci.getFieldInfo().forEach(field -> {
//                    String fieldType = field.getTypeDescriptor().toString();
//                    // fieldType might be like "com.walpro.autobuilder.core.lifecycle.CycleContextData"
//                    if (fieldType.startsWith(basePackage)) {
//                        lines.add("\"" + from + "\" --> \"" + fieldType + "\"");
//                    }
//                });
//            }
//
//            // Emit collected relations
//            for (String line : lines) {
//                sb.append(line).append("\n");
//            }
//
//            sb.append("@enduml\n");
//        }
//
//        Files.writeString(out, sb.toString());
//        System.out.println("Wrote PlantUML diagram to " + out.toAbsolutePath());
//    }
//}

package com.walpro.autobuilder.tools;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.ScanResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Hybrid architecture diagram generator:
 * - groups classes into subsystem packages
 * - emits only key classes (heuristic) + anything they depend on (internal)
 * - emits only meaningful edges (extends/implements + field-type deps)
 */
public class DiagramGenerator {

    private static final String BASE = "com.walpro.autobuilder";

    public static void main(String[] args) throws IOException {
        Path out = Path.of("diagrams/autobuilder-hybrid.puml");
        Files.createDirectories(out.getParent());

        try (ScanResult scan = new ClassGraph()
                .enableAllInfo()
                .acceptPackages(BASE)
                .scan()) {

            ClassInfoList all = scan.getAllClasses();

            // 1) Choose "key" classes (hybrid mode)
            Set<String> selected = selectKeyClasses(all);

            // 2) Expand a bit: include direct internal deps of key classes (keeps graph connected)
            selected = expandByDirectInternalDependencies(all, selected, 1);

            // 3) Emit PlantUML grouped by subsystem
            String puml = toPlantUmlHybrid(all, selected);

            Files.writeString(out, puml);
            System.out.println("Wrote PlantUML diagram to " + out.toAbsolutePath());
        }
    }

    private static Set<String> selectKeyClasses(ClassInfoList all) {
        Set<String> selected = new LinkedHashSet<>();

        for (ClassInfo ci : all) {
            if (!ci.getName().startsWith(BASE + ".")) continue;

            // skip annotations and synthetic types
            if (ci.isAnnotation() || ci.isSynthetic()) continue;

            if (ci.getName().contains("$")) continue;
            if (ci.isEnum()) continue;

            String n = ci.getName();

            // keep important architectural types
            if (matchesAny(n,
                    ".core.", ".agent.", ".analysis.", ".diff.", ".graph.", ".goal.", ".client.", ".persistence.", ".service.", ".parser."
            ) && looksArchitectural(n)) {
                selected.add(n);
            }

            // explicitly keep “role anchors” if present
            if (matchesAnySimpleName(ci, "CycleExecutor", "AutobuilderService",
                    "DefaultEvaluationAgent", "DefaultEvolutionAgent", "DefaultPlannerAgent", "DefaultMergeAgent",
                    "EvaluationCoordinator", "EvolutionCoordinator", "PlannerCoordinator", "MergeCoordinator",
                    "EvaluationInterpreter", "LLMClient", "LLMAgentProtocolInvoker", "MetaAgent", "MetaAgentManager")) {
                selected.add(n);
            }
        }

        return selected;
    }

    private static boolean looksArchitectural(String fqcn) {
        String simple = simpleName(fqcn);

        // include “role” types
        if (containsAny(simple,
                "Agent", "Coordinator", "Executor", "Service", "Interpreter",
                "Protocol", "Contract", "Facade", "Invoker", "Planner", "Merger", "Diff", "Graph", "Registry"
        )) return true;

        // include “outcome/results” (useful but limited)
        if (containsAny(simple, "Outcome", "Result", "State", "Signal")) return true;

        // exclude pure data bags / noise (tweak as needed)
        if (containsAny(simple,
                "Dto", "DTO", "Request", "Response", "Builder", "Config", "Configuration", "Properties"
        )) return false;

        // default: not architectural
        return false;
    }

    private static Set<String> expandByDirectInternalDependencies(ClassInfoList all, Set<String> selected, int depth) {
        Map<String, ClassInfo> byName = new HashMap<>();
        for (ClassInfo ci : all) byName.put(ci.getName(), ci);

        Set<String> current = new LinkedHashSet<>(selected);

        for (int d = 0; d < depth; d++) {
            Set<String> add = new LinkedHashSet<>();
            for (String n : current) {
                ClassInfo ci = byName.get(n);
                if (ci == null) continue;

                // superclass
                ClassInfo sc = ci.getSuperclass();
                if (sc != null && sc.getName().startsWith(BASE + ".")) add.add(sc.getName());

                // interfaces
                ci.getInterfaces().forEach(i -> {
                    if (i.getName().startsWith(BASE + ".")) add.add(i.getName());
                });

                // field types
                for (FieldInfo f : ci.getFieldInfo()) {
                    String ft = f.getTypeDescriptor().toString();
                    if (ft.startsWith(BASE + ".")) add.add(ft);
                }
            }
            current.addAll(add);
        }

        return current;
    }

    private static String toPlantUmlHybrid(ClassInfoList all, Set<String> selected) {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("set namespaceSeparator .\n");
        sb.append("skinparam packageStyle rectangle\n");
        sb.append("skinparam shadowing false\n");
        sb.append("hide empty members\n\n");

        // group selected classes by subsystem label
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String cls : selected) {
            groups.computeIfAbsent(subsystemOf(cls), k -> new ArrayList<>()).add(cls);
        }

        // emit packages + class declarations
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            sb.append("package \"").append(e.getKey()).append("\" {\n");
            e.getValue().sort(Comparator.naturalOrder());
            for (String fqcn : e.getValue()) {
                if (fqcn.contains("[]")) continue;
                sb.append("  class \"").append(fqcn).append("\" as ").append(alias(fqcn)).append("\n");
            }
            sb.append("}\n\n");
        }

        // emit filtered relations
        Set<String> edges = new LinkedHashSet<>();
        Map<String, ClassInfo> byName = new HashMap<>();
        for (ClassInfo ci : all) byName.put(ci.getName(), ci);

        for (String n : selected) {
            ClassInfo ci = byName.get(n);
            if (ci == null) continue;

            // extends
            ClassInfo sc = ci.getSuperclass();
            if (sc != null && selected.contains(sc.getName())) {
                edges.add(alias(sc.getName()) + " <|-- " + alias(n));
            }

            // implements
            ci.getInterfaces().forEach(i -> {
                if (selected.contains(i.getName())) {
                    edges.add(alias(i.getName()) + " <|.. " + alias(n));
                }
            });

            // field deps (only if it crosses subsystem boundary OR target is clearly architectural)
            for (FieldInfo f : ci.getFieldInfo()) {
                String ft = f.getTypeDescriptor().toString();
                if (ft.contains("[]")) continue;
                if (n.equals(ft)) continue;
                if (!selected.contains(ft)) continue;

                String fromS = subsystemOf(n);
                String toS = subsystemOf(ft);

//                if (!fromS.equals(toS) || looksArchitectural(ft)) {
//                    edges.add(alias(n) + " --> " + alias(ft));
//                }
                if (!fromS.equals(toS)) {
                    edges.add(alias(n) + " --> " + alias(ft));
                }
            }
        }

        for (String edge : edges) sb.append(edge).append("\n");

        sb.append("@enduml\n");
        return sb.toString();
    }

    private static String subsystemOf(String fqcn) {
        if (fqcn.contains(".agent.evaluation.")) return "Agent: Evaluation";
        if (fqcn.contains(".agent.evolution.")) return "Agent: Evolution";
        if (fqcn.contains(".agent.planner.")) return "Agent: Planner";
        if (fqcn.contains(".agent.merge.")) return "Agent: Merge";
        if (fqcn.contains(".agent.")) return "Agent: Shared";

        if (fqcn.contains(".core.coordinator.")) return "Core: Coordinators";
        if (fqcn.contains(".core.lifecycle.")) return "Core: Lifecycle";
        if (fqcn.contains(".core.engine.")) return "Core: Engine";
        if (fqcn.contains(".core.")) return "Core: Other";

        if (fqcn.contains(".analysis.diff.") || fqcn.contains(".diff.")) return "Diff";
        if (fqcn.contains(".analysis.")) return "Analysis";

        if (fqcn.contains(".graph.")) return "Graph";
        if (fqcn.contains(".goal.")) return "Goal";

        if (fqcn.contains(".client.")) return "LLM Client";
        if (fqcn.contains(".meta.")) return "Meta";

        if (fqcn.contains(".persistence.")) return "Persistence";
        if (fqcn.contains(".service.")) return "Services";
        if (fqcn.contains(".parser.")) return "Parser";
        if (fqcn.contains(".config.")) return "Config";
        if (fqcn.contains(".util.")) return "Util";

        return "Other";
    }

    private static String alias(String fqcn) {
        String a = fqcn;

        // normalize common bytecode-ish suffixes
        a = a.replace("[]", "_Array");

        // make a safe identifier
        a = a.replace('.', '_')
                .replace('$', '_')
                .replace('[', '_')
                .replace(']', '_')
                .replace('<', '_')
                .replace('>', '_')
                .replace(',', '_')
                .replace(' ', '_');

        return "C_" + a;
    }

    private static String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    private static boolean matchesAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    private static boolean matchesAnySimpleName(ClassInfo ci, String... names) {
        String sn = ci.getSimpleName();
        for (String n : names) if (sn.equals(n)) return true;
        return false;
    }
}
