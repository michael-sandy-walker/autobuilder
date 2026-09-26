package com.walpro.autobuilder.analysis.structure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.visitor.GenericVisitorAdapter;
import com.walpro.autobuilder.analysis.diff.model.ElementType;
import com.walpro.autobuilder.analysis.structure.model.ParsedElement;
import com.walpro.autobuilder.analysis.structure.model.ParsedElementBuilder;

import java.util.*;

public class JavaStructureParser {

    private static final JavaParser parser = new JavaParser();

    public static ParsedElement parse(String path, String code) {
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow(() ->
                new IllegalArgumentException("Invalid Java source: " + path));

        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getName().asString())
                .orElse("");

        List<ParsedElement> children = new ArrayList<>();

        for (TypeDeclaration<?> type : cu.getTypes()) {
            children.add(parseType(type, packageName));
        }

        return new ParsedElementBuilder()
                .id(path)
                .type(ElementType.FILE)
                .content(code)
                .putMeta("path", path)
                .addChildren(children)
                .build();
    }

    private static ParsedElement parseType(TypeDeclaration<?> type, String pkg) {
        String fqcn = pkg.isEmpty() ? type.getNameAsString() : pkg + "." + type.getNameAsString();

        ElementType elementType;
        if (type instanceof ClassOrInterfaceDeclaration ci) {
            elementType = ci.isInterface() ? ElementType.INTERFACE : ElementType.CLASS;
        } else if (type instanceof EnumDeclaration) {
            elementType = ElementType.CLASS; // or add ENUM if we define it
        } else if (type instanceof RecordDeclaration) {
            elementType = ElementType.CLASS; // records are nominal classes
        } else if (type instanceof AnnotationDeclaration) {
            elementType = ElementType.ANNOTATION;
        } else {
            elementType = ElementType.UNKNOWN;
        }

        ParsedElementBuilder classBuilder = new ParsedElementBuilder()
                .id(fqcn)
                .type(elementType)
                .content(type.toString());

        for (BodyDeclaration<?> member : type.getMembers()) {
            member.accept(new GenericVisitorAdapter<Void, Void>() {
                @Override
                public Void visit(MethodDeclaration md, Void arg) {
                    classBuilder.addChild(new ParsedElementBuilder()
                            .id(fqcn + "." + md.getNameAsString())
                            .type(ElementType.METHOD)
                            .content(md.toString())
                            .putMeta("returnType", md.getType().asString())
                            .putMeta("modifiers", md.getModifiers().toString())
                            .putMeta("line", md.getBegin().map(p -> p.line).orElse(-1))
                            .build());
                    return null;
                }

                @Override
                public Void visit(FieldDeclaration fd, Void arg) {
                    fd.getVariables().forEach(var ->
                        classBuilder.addChild(new ParsedElementBuilder()
                                .id(fqcn + "." + var.getNameAsString())
                                .type(ElementType.FIELD)
                                .content(fd.toString())
                                .putMeta("fieldType", fd.getElementType().asString())
                                .putMeta("modifiers", fd.getModifiers().toString())
                                .putMeta("line", fd.getBegin().map(p -> p.line).orElse(-1))
                                .build()));
                    return null;
                }

                @Override
                public Void visit(ConstructorDeclaration cd, Void arg) {
                    classBuilder.addChild(new ParsedElementBuilder()
                            .id(fqcn + "." + cd.getNameAsString())
                            .type(ElementType.CONSTRUCTOR)
                            .content(cd.toString())
                            .putMeta("modifiers", cd.getModifiers().toString())
                            .putMeta("line", cd.getBegin().map(p -> p.line).orElse(-1))
                            .build());
                    return null;
                }
            }, null);
        }

        return classBuilder.build();
    }
}
