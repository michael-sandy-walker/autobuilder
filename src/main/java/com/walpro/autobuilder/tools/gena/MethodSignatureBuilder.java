package com.walpro.autobuilder.tools.gena;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.stream.Collectors;

public class MethodSignatureBuilder {

    public static String build(MethodDeclaration method) {
        String params = method.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
        return method.getNameAsString() + "(" + params + "): " + method.getType().asString();
    }

    public static String build(ConstructorDeclaration ctor) {
        String params = ctor.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
        return ctor.getNameAsString() + "(" + params + ")";
    }
}
