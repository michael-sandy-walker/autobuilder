package com.walpro.autobuilder.tools.gena;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.utils.SourceRoot;
import com.walpro.autobuilder.tools.diff.ChangedMethod;
import com.walpro.autobuilder.tools.diff.ClassChangeAnalyzer;
import com.walpro.autobuilder.tools.diff.ClassChangeResult;

import lombok.Data;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class GENASummarizer {
	
	public void test() {}

    public static void main(String[] args) throws Exception {
    	String baseDir = "gena";
    	new File(baseDir + "/full").mkdirs();
    	new File(baseDir + "/packages").mkdirs();

    	
        File projectRoot = new File("src/main/java"); // Adjust if needed
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new JavaParserTypeSolver(projectRoot));
        
        ParserConfiguration config = new ParserConfiguration()
        		.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
        		.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        StaticJavaParser.setConfiguration(config);

        List<CompilationUnit> units = new ArrayList<>();

        Files.walk(projectRoot.toPath())
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(path -> {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(path);
                    units.add(cu);
                    System.out.println("✅ Parsed: " + path);
                } catch (Exception e) {
                    System.err.println("⚠️ Failed to parse: " + path + " → " + e.getMessage());
                }
            });
        
        System.out.println("🔎 Parsed units: " + units.size());
        units.forEach(unit -> {
            unit.getPrimaryTypeName().ifPresent(name ->
                System.out.println("  - " + name + " @ " + unit.getStorage().map(s -> s.getPath().toString()).orElse("unknown")));
        });

        ProjectStructure structure = new ProjectStructure();
        FileHashTracker hashTracker = new FileHashTracker();
        GoalOverlayLoader goalOverlay = new GoalOverlayLoader();
        FileSnapshotStore snapshotStore = new FileSnapshotStore(Paths.get("gena/cache/previous"));


        for (CompilationUnit cu : units) {
            processUnit(cu, structure, hashTracker, snapshotStore);
        }

        writeJson(structure, baseDir + "/full/project-summary.json");
        writeLeanSummary(structure, baseDir + "/full/project-summary.lean.json", goalOverlay);
        writeLLMFormat(structure, baseDir + "/full/human-summary.txt");
        writePerPackageSummaries(structure, baseDir + "/packages");
        
        writeChangedSummary(structure, hashTracker, baseDir + "/full/project-summary.changed.json");
        writeChangedLeanSummary(structure, hashTracker, baseDir + "/full/project-summary.changed.lean.json", goalOverlay, snapshotStore);
        writeChangedSummaryTxt(structure, hashTracker, goalOverlay, baseDir + "/full/project-summary.changed.txt");
        
        for (ClassSummary cls : structure.getClasses()) {
            if (hashTracker.hasChanged(cls.getFilePath())) {
                snapshotStore.savePreviousVersion(cls.getFilePath()); // Save new snapshot AFTER diff
            }
        }
        hashTracker.save(); // persist hash cache
    }

    private static void processUnit(CompilationUnit cu, ProjectStructure structure, FileHashTracker hashTracker, FileSnapshotStore snapshotStore) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = clazz.getFullyQualifiedName().orElse("[Anonymous]");
            Optional<CompilationUnit.Storage> storageOpt = cu.getStorage();

            if (storageOpt.isEmpty()) {
                System.err.printf("⚠️ [GENA] Skipping class '%s' — could not resolve file storage.%n", className);
                return; // Skip problematic class
            }

            File file = storageOpt.get().getPath().toFile();
            String filePath = file.getAbsolutePath();
            String fileHash = FileHashCalculator.compute(file);

            List<String> classModifiers = clazz.getModifiers().stream().map(Object::toString).toList();
            
            hashTracker.track(filePath, fileHash);
            
            ClassSummary classSummary = new ClassSummary();
            classSummary.setClassName(className);
            classSummary.setFilePath(filePath);
            classSummary.setFileHash(fileHash);
            classSummary.setModifiers(classModifiers);
            classSummary.setComment(CommentExtractor.getCleanComment(clazz));

            for (FieldDeclaration field : clazz.getFields()) {
                for (VariableDeclarator var : field.getVariables()) {
                    FieldSummary fs = new FieldSummary();
                    fs.setName(var.getNameAsString());
                    fs.setType(var.getTypeAsString());
                    fs.setLine(var.getBegin().map(p -> p.line).orElse(-1));
                    fs.setModifiers(field.getModifiers().stream().map(Object::toString).toList());
                    classSummary.getFields().add(fs);
                }
            }

            for (ConstructorDeclaration ctor : clazz.getConstructors()) {
                ConstructorSummary cs = new ConstructorSummary();
                cs.setSignature(MethodSignatureBuilder.build(ctor));
                cs.setLine(ctor.getBegin().map(p -> p.line).orElse(-1));
                cs.setModifiers(ctor.getModifiers().stream().map(Object::toString).toList());
                cs.setComment(CommentExtractor.getCleanComment(ctor));
                classSummary.getConstructors().add(cs);
            }

            for (MethodDeclaration method : clazz.getMethods()) {
                MethodSummary methodSummary = new MethodSummary();
                methodSummary.setName(method.getNameAsString());
                methodSummary.setLine(method.getBegin().map(p -> p.line).orElse(-1));
                methodSummary.setModifiers(method.getModifiers().stream().map(Object::toString).toList());
                methodSummary.setSignature(MethodSignatureBuilder.build(method));
                methodSummary.setComment(CommentExtractor.getCleanComment(method));

                Set<String> calls = new TreeSet<>();
                method.findAll(MethodCallExpr.class).forEach(call -> calls.add(call.getNameAsString()));
                methodSummary.setCalls(new ArrayList<>(calls));

                classSummary.getMethods().add(methodSummary);
            }

            structure.getClasses().add(classSummary);
        });
    }

    private static void writeJson(ProjectStructure structure, String filename) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(new File(filename), structure);
    }

    private static void writeLLMFormat(ProjectStructure structure, String filename) throws Exception {
        try (FileWriter writer = new FileWriter(filename)) {
            for (ClassSummary cls : structure.getClasses()) {
                writer.write("CLASS: " + cls.getClassName() + "\n");
                writer.write("FILE:  " + cls.getFilePath() + "\n");
                writer.write("HASH:  " + cls.getFileHash() + "\n");
                if (cls.getComment() != null) {
                    writer.write("DESC:  " + cls.getComment().replace("\n", " ") + "\n");
                }
                for (MethodSummary method : cls.getMethods()) {
                    writer.write("  + " + method.getSignature() + " (line " + method.getLine() + ")");
                    if (!method.getCalls().isEmpty()) {
                        writer.write(" -> calls: " + String.join(", ", method.getCalls()));
                    }
                    writer.write("\n");
                }
                writer.write("\n");
            }
        }
    }
    
    private static void writePerPackageSummaries(ProjectStructure structure, String outputRoot) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Map<String, List<ClassSummary>> byPackage = new TreeMap<>();

        for (ClassSummary cls : structure.getClasses()) {
            String pkg = cls.getClassName().contains(".")
                    ? cls.getClassName().substring(0, cls.getClassName().lastIndexOf('.'))
                    : "[default]";
            byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(cls);
        }

        for (Map.Entry<String, List<ClassSummary>> entry : byPackage.entrySet()) {
            String packageName = entry.getKey();
            List<ClassSummary> classes = entry.getValue();

            File outFile = new File(outputRoot, packageName.replace('.', File.separatorChar) + ".json");
            outFile.getParentFile().mkdirs();

            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("package", packageName);
            wrapper.put("classes", classes);

            mapper.writeValue(outFile, wrapper);
        }
    }
    
    private static void writeLeanSummary(ProjectStructure structure, String outputPath, GoalOverlayLoader goalOverlay) throws Exception {
        List<Map<String, Object>> compressed = new ArrayList<>();

        for (ClassSummary cls : structure.getClasses()) {
            Map<String, Object> clsMap = new LinkedHashMap<>();
            clsMap.put("c", cls.getClassName());      // class name
            clsMap.put("f", cls.getFilePath());       // file path
            List<String> goals = goalOverlay.getGoalsFor(cls.getClassName());
            if (!goals.isEmpty()) {
                clsMap.put("g", goals);
            }

            List<Map<String, Object>> methods = new ArrayList<>();
            for (MethodSummary m : cls.getMethods()) {
                Map<String, Object> mMap = new LinkedHashMap<>();
                mMap.put("s", m.getSignature());      // signature
                if (!m.getCalls().isEmpty()) {
                    mMap.put("c", m.getCalls());      // calls
                }
                methods.add(mMap);
            }
            clsMap.put("m", methods);

            compressed.add(clsMap);
        }

        ObjectMapper mapper = new ObjectMapper(); // no pretty printing
        mapper.writeValue(new File(outputPath), compressed);
    }
    
    private static void writeChangedSummary(ProjectStructure structure, FileHashTracker tracker, String outputPath) throws Exception {
        List<ClassSummary> changed = new ArrayList<>();

        for (ClassSummary cls : structure.getClasses()) {
            if (tracker.hasChanged(cls.getFilePath())) {
                changed.add(cls);
            }
        }

        if (!changed.isEmpty()) {
            ProjectStructure subset = new ProjectStructure();
            subset.getClasses().addAll(changed);

            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(new File(outputPath), subset);
        }
    }
    
    private static void writeChangedLeanSummary(ProjectStructure structure, FileHashTracker tracker, String outputPath, GoalOverlayLoader goalOverlay, FileSnapshotStore snapshotStore) throws Exception {
        List<Map<String, Object>> changedLean = new ArrayList<>();

        for (GENASummarizer.ClassSummary cls : structure.getClasses()) {
            if (tracker.hasChanged(cls.getFilePath())) {
                Map<String, Object> clsMap = new LinkedHashMap<>();
                clsMap.put("c", cls.getClassName());
                clsMap.put("f", cls.getFilePath());

                List<String> goals = goalOverlay.getGoalsFor(cls.getClassName());
                if (!goals.isEmpty()) {
                    clsMap.put("g", goals);
                }

                String oldSource = snapshotStore.loadPreviousVersion(cls.getFilePath());
                String newSource = Files.readString(Paths.get(cls.getFilePath()));

                List<Map<String, Object>> methods = new ArrayList<>();
                if (oldSource != null) {
                    ClassChangeResult result = ClassChangeAnalyzer.compareMethods(oldSource, newSource);

                    for (MethodSummary m : cls.getMethods()) {
                        Map<String, Object> mMap = new LinkedHashMap<>();
                        mMap.put("s", m.getSignature());
                        if (!m.getCalls().isEmpty()) {
                            mMap.put("c", m.getCalls());
                        }
                        if (result.getAdded().contains(m.getSignature())) {
                            mMap.put("t", "added");
                        } else if (result.getModified().contains(m.getSignature())) {
                            mMap.put("t", "modified");
                        }
                        methods.add(mMap);
                    }

                    for (ChangedMethod removedSig : result.getRemoved()) {
                        Map<String, Object> removedMap = new LinkedHashMap<>();
                        removedMap.put("s", removedSig.name());
                        removedMap.put("t", "removed");
                        methods.add(removedMap);
                    }
                } else {
                    for (MethodSummary m : cls.getMethods()) {
                        Map<String, Object> mMap = new LinkedHashMap<>();
                        mMap.put("s", m.getSignature());
                        if (!m.getCalls().isEmpty()) {
                            mMap.put("c", m.getCalls());
                        }
                        mMap.put("t", "added");
                        methods.add(mMap);
                    }
                }

                clsMap.put("m", methods);
                changedLean.add(clsMap);
            }
        }

        if (!changedLean.isEmpty()) {
            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(new File(outputPath), changedLean);
        }
    }

    
    private static void writeChangedSummaryTxt(ProjectStructure structure, FileHashTracker tracker, GoalOverlayLoader overlay, String outputPath) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("=== Changed Classes ===\n");

        FileSnapshotStore snapshotStore = new FileSnapshotStore(Paths.get("gena/cache/previous"));

        for (ClassSummary cls : structure.getClasses()) {
            if (tracker.hasChanged(cls.getFilePath())) {
                lines.add(cls.getClassName());

                List<String> goals = overlay.getGoalsFor(cls.getClassName());
                if (!goals.isEmpty()) {
                    lines.add("  ↳ Goals: " + goals);
                }

                lines.add("  ↳ File: " + cls.getFilePath());

                String oldSource = snapshotStore.loadPreviousVersion(cls.getFilePath());
                String newSource = Files.readString(Paths.get(cls.getFilePath()));

                if (oldSource != null) {
                    ClassChangeResult result = ClassChangeAnalyzer.compareMethods(oldSource, newSource);
                    boolean any = false;

                    if (!result.getAdded().isEmpty()) {
                        lines.add("    + Added methods:");
                        for (ChangedMethod m : result.getAdded()) {
                            lines.add("      - " + m.name());
                        }
                        any = true;
                    }

                    if (!result.getModified().isEmpty()) {
                        lines.add("    ~ Modified methods:");
                        for (ChangedMethod m : result.getModified()) {
                            lines.add("      ~ " + m.name());
                        }
                        any = true;
                    }

                    if (!result.getRemoved().isEmpty()) {
                        lines.add("    - Removed methods:");
                        for (ChangedMethod m : result.getRemoved()) {
                            lines.add("      × " + m.name());
                        }
                        any = true;
                    }
                    
                    if (!any) {
                        lines.add("    ✓ No method-level changes detected");
                    }
                } else {
                    lines.add("    ⚠ No previous version found — all methods treated as new.");
                    for (MethodSummary m : cls.getMethods()) {
                        lines.add("      + " + m.getSignature());
                    }
                }

                lines.add("");
            }
        }

        if (lines.size() > 1) {
            File out = new File(outputPath);
            out.getParentFile().mkdirs();
            Files.write(out.toPath(), lines);
        }
    }

    // ======= Data Classes =======

    @Data
    public static class ProjectStructure {
        private List<ClassSummary> classes = new ArrayList<>();
    }

    @Data
    public static class ClassSummary {
        private String className;
        private String filePath;
        private String fileHash;
        private List<String> modifiers;
        private String comment;
        private List<FieldSummary> fields = new ArrayList<>();
        private List<ConstructorSummary> constructors = new ArrayList<>();
        private List<MethodSummary> methods = new ArrayList<>();
    }

    @Data
    public static class FieldSummary {
        private String name;
        private String type;
        private int line;
        private List<String> modifiers;
    }

    @Data
    public static class ConstructorSummary {
        private String signature;
        private int line;
        private List<String> modifiers;
        private String comment;
    }

    @Data
    public static class MethodSummary {
        private String name;
        private int line;
        private String signature;
        private List<String> modifiers;
        private List<String> calls = new ArrayList<>();
        private String comment;
    }
}
