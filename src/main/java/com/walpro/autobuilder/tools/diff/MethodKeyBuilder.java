package com.walpro.autobuilder.tools.diff;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;

import java.util.stream.Collectors;

public class MethodKeyBuilder {

    public static String build(MethodDeclaration m) {
        String methodName = m.getNameAsString();
        String paramTypes = m.getParameters().stream()
                .map(MethodKeyBuilder::resolveType)
                .collect(Collectors.joining(","));
        return methodName + "(" + paramTypes + ")";
    }
    
    private static String resolveType(Parameter p) {
        try {
            return p.getType().resolve().describe(); // fully qualified
        } catch (Exception e) {
            return p.getType().asString(); // fallback to simple name
        }
    }
}
