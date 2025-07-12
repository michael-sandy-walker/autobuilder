package com.walpro.autobuilder.tools;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.utils.SourceRoot;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;

public class ASTSummaryGenerator {

    private static final Map<String, Set<String>> classMethods = new TreeMap<>();
    private static final Map<String, Set<String>> methodCalls = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        File projectRoot = new File("src/main/java"); // Adjust if needed
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new JavaParserTypeSolver(projectRoot));
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);

        SourceRoot sourceRoot = new SourceRoot(Paths.get(projectRoot.toURI()));
        sourceRoot.tryToParse();

        sourceRoot.getCompilationUnits().forEach(ASTSummaryGenerator::processUnit);

        printClassMethodSummary();
        printMethodCallGraph();
    }

    private static void processUnit(CompilationUnit cu) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = clazz.getFullyQualifiedName().orElse("[Anonymous]");
            Set<String> methods = new TreeSet<>();
            classMethods.putIfAbsent(className, methods);

            clazz.getMethods().forEach(method -> {
                String methodName = method.getNameAsString() + "()";
                methods.add(methodName);

                String fullMethod = className + "." + methodName;
                Set<String> calls = new TreeSet<>();
                method.findAll(MethodCallExpr.class).forEach(call -> {
                    calls.add(call.getNameAsString() + "()");
                });
                if (!calls.isEmpty()) {
                    methodCalls.put(fullMethod, calls);
                }
            });
        });
    }

    private static void printClassMethodSummary() {
        System.out.println("=== CLASS AND METHOD SUMMARY ===");
        classMethods.forEach((className, methods) -> {
            System.out.println(className + ":");
            methods.forEach(m -> System.out.println("  - " + m));
        });
    }

    private static void printMethodCallGraph() {
        System.out.println("\n=== METHOD CALL GRAPH ===");
        methodCalls.forEach((method, calls) -> {
            System.out.println(method + " calls:");
            calls.forEach(c -> System.out.println("  -> " + c));
        });
    }
}
