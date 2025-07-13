package com.walpro.autobuilder.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DefaultSemanticDiffAnalyzer implements SemanticDiffAnalyzer {

    @Override
    public boolean isStructurallySignificant(FileMutation oldMutation, FileMutation newMutation) {
        if (oldMutation == null || newMutation == null) return false;

        String path = newMutation.path();
        if (path == null || !path.equals(oldMutation.path())) return false;

        String oldContent = safe(oldMutation.content());
        String newContent = safe(newMutation.content());

        if (oldContent.equals(newContent)) return false;

        try {
            if (path.endsWith(".java")) {
                return hasApiAnnotationDifference(oldContent, newContent);
            }

            if (path.endsWith("pom.xml")) {
                return containsDependencyDiff(oldContent, newContent);
            }
        } catch (Exception e) {
            log.warn("Semantic diff failed for {}: {}", path, e.getMessage());
        }

        return false;
    }

    private boolean hasApiAnnotationDifference(String oldJava, String newJava) {
        Set<String> highImpactAnnotations = Set.of(
            "RestController", "Controller", "Service", "Component",
            "Repository", "Configuration", "Transactional", "Scheduled",
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping"
        );

        Set<String> oldAnnotations = extractAnnotations(oldJava, highImpactAnnotations);
        Set<String> newAnnotations = extractAnnotations(newJava, highImpactAnnotations);

        return !oldAnnotations.equals(newAnnotations);
    }

    private Set<String> extractAnnotations(String content, Set<String> allowlist) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            return cu.findAll(MethodDeclaration.class).stream()
                .flatMap(method -> method.getAnnotations().stream())
                .map(a -> a.getName().getIdentifier())
                .filter(allowlist::contains)
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.debug("Annotation extraction failed: {}", e.getMessage());
            return Set.of();
        }
    }

    private boolean containsDependencyDiff(String oldXml, String newXml) {
        return countSemanticDependencies(newXml) > countSemanticDependencies(oldXml);
    }

    private int countSemanticDependencies(String xml) {
        return count(xml, "<dependency>") +
               count(xml, "<groupId>org.springframework.boot</groupId>") +
               count(xml, "<groupId>org.springdoc</groupId>") +
               count(xml, "<groupId>org.mapstruct</groupId>") +
               count(xml, "<groupId>com.fasterxml.jackson") +
               count(xml, "<groupId>com.github.javaparser") +
               count(xml, "<artifactId>neo4j") +
               count(xml, "<artifactId>security") +
               count(xml, "<artifactId>swagger") +
               count(xml, "<artifactId>graphql");
    }

    private int count(String content, String token) {
        int total = 0, idx = 0;
        while ((idx = content.indexOf(token, idx)) != -1) {
            total++;
            idx += token.length();
        }
        return total;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
