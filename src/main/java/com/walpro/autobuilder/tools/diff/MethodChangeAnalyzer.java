package com.walpro.autobuilder.tools.diff;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.walpro.autobuilder.tools.tracker.MethodHashTracker;

import java.util.*;
import java.util.stream.Collectors;

public class MethodChangeAnalyzer {

    public static List<ChangedMethod> compareMethodBodies(
            String filePath,
            String oldSource,
            String newSource,
            MethodHashTracker methodHashTracker
    ) {
    	if (!filePath.endsWith("java")) { // TODO: Add comparisons for other file types here (MW; 2025-JUL-14)

    		return Collections.emptyList();
    	}
        Map<String, MethodDeclaration> oldMethods = parseMethods(oldSource);
        Map<String, MethodDeclaration> newMethods = parseMethods(newSource);

        List<ChangedMethod> result = new ArrayList<>();

        Set<String> allSignatures = new HashSet<>();
        allSignatures.addAll(oldMethods.keySet());
        allSignatures.addAll(newMethods.keySet());

        for (String signature : allSignatures) {
            MethodDeclaration oldMethod = oldMethods.get(signature);
            MethodDeclaration newMethod = newMethods.get(signature);

            if (oldMethod != null && newMethod != null) {
                String oldHash = hashBody(oldMethod);
                String newHash = hashBody(newMethod);
                methodHashTracker.track(filePath, signature, newHash);

                if (!oldHash.equals(newHash)) {
                    result.add(new ChangedMethod(signature, ChangedMethod.ChangeType.MODIFIED,
                            normalizeBody(oldMethod), normalizeBody(newMethod)));
                } else {
                	result.add(new ChangedMethod(signature, ChangedMethod.ChangeType.UNCHANGED,
                            normalizeBody(oldMethod), normalizeBody(newMethod)));
                }
            } else if (oldMethod == null && newMethod != null) {
                String newHash = hashBody(newMethod);
                methodHashTracker.track(filePath, signature, newHash);

                result.add(new ChangedMethod(signature, ChangedMethod.ChangeType.ADDED,
                        null, normalizeBody(newMethod)));
            } else if (oldMethod != null) {
                result.add(new ChangedMethod(signature, ChangedMethod.ChangeType.REMOVED,
                        normalizeBody(oldMethod), null));
            }
        }

        return result;
    }

    private static Map<String, MethodDeclaration> parseMethods(String source) {
        if (source == null || source.isBlank()) return Collections.emptyMap();
        CompilationUnit cu = StaticJavaParser.parse(source);
        return cu.findAll(MethodDeclaration.class).stream()
        		.collect(Collectors.toMap(
        			    MethodKeyBuilder::build,
        			    m -> m,
        			    (a, b) -> b
        			));
    }

    private static String hashBody(MethodDeclaration method) {
        return Integer.toHexString(normalizeBody(method).hashCode());
    }

    private static String normalizeBody(MethodDeclaration method) {
        return method.getBody()
                .map(b -> b.toString().replaceAll("\\s+", " ").trim())
                .orElse("");
    }
}
