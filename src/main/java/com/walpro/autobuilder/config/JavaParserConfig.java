package com.walpro.autobuilder.config;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class JavaParserConfig {

    private final ParserConfigProperties props;

    @PostConstruct
    public void configureJavaSymbolSolver() {
        File sourceDir = new File(props.getSourceRoot());
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            // T6-T: without a source root there is no symbol solver to configure, but the level the static parser
            // reads at is not the source root's business: left at the library's default it reads no record.
            StaticJavaParser.setConfiguration(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
            log.warn("JavaParser source root not found: {}", sourceDir.getAbsolutePath());
            return;
        }

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new JavaParserTypeSolver(sourceDir));

        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        StaticJavaParser.setConfiguration(config);
        log.info("JavaSymbolSolver configured with source root: {}", sourceDir.getAbsolutePath());
    }
}
