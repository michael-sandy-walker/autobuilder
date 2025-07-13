package com.walpro.autobuilder.tools.diff;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.*;
import java.util.stream.Collectors;

public class ClassChangeAnalyzer {

    public static ClassChangeResult compareMethods(String oldSource, String newSource) {
        List<MethodDeclaration> oldMethods = extractMethods(oldSource);
        List<MethodDeclaration> newMethods = extractMethods(newSource);

        Map<String, String> oldMap = toNormalizedMethodMap(oldMethods);
        Map<String, String> newMap = toNormalizedMethodMap(newMethods);

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(oldMap.keySet());
        allKeys.addAll(newMap.keySet());

        List<ChangedMethod> changes = new ArrayList<>();

        for (String key : allKeys) {
            if (!oldMap.containsKey(key)) {
                changes.add(new ChangedMethod(key, ChangedMethod.ChangeType.ADDED));
            } else if (!newMap.containsKey(key)) {
                changes.add(new ChangedMethod(key, ChangedMethod.ChangeType.REMOVED));
            } else if (!Objects.equals(oldMap.get(key), newMap.get(key))) {
                changes.add(new ChangedMethod(key, ChangedMethod.ChangeType.MODIFIED));
            }
        }

        return new ClassChangeResult(changes);
    }

    private static List<MethodDeclaration> extractMethods(String source) {
        if (source == null) {
            return Collections.emptyList();
        }
        CompilationUnit cu = StaticJavaParser.parse(source);
        return cu.findAll(MethodDeclaration.class);
    }

    private static Map<String, String> toNormalizedMethodMap(List<MethodDeclaration> methods) {
        return methods.stream().collect(Collectors.toMap(
            MethodKeyBuilder::build,
            ClassChangeAnalyzer::normalize,
            (existing, replacement) -> replacement
        ));
    }

    /**
     * Normalize method for diffing. Keeps annotations/modifiers/etc. to detect MODIFIED.
     */
    private static String normalize(MethodDeclaration method) {
        String annotations = method.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .sorted()
                .collect(Collectors.joining(","));

        String modifiers = method.getModifiers().stream()
                .map(Modifier::toString)
                .map(String::trim)
                .sorted()
                .collect(Collectors.joining(","));

        String signature = method.getDeclarationAsString(false, false, false).replaceAll("\\s+", " ").trim();
        String body = method.getBody().map(b -> b.toString().replaceAll("\\s+", " ").trim()).orElse("");

        return annotations + "|" + modifiers + "|" + signature + "|" + body;
    }
}
