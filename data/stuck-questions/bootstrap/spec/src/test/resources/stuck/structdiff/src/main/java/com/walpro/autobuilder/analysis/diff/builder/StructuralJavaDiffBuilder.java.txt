package com.walpro.autobuilder.analysis.diff.builder;

import com.walpro.autobuilder.analysis.diff.model.*;
import com.walpro.autobuilder.analysis.structure.JavaStructureParser;
import com.walpro.autobuilder.analysis.structure.model.ParsedElement;

public class StructuralJavaDiffBuilder {

    public static StructuralDiffNode diffJavaFile(
            String relPath,
            String oldContent,
            String newContent
    ) {
        // Parse old and new as ParsedElement trees
        ParsedElement oldRoot = oldContent != null ? JavaStructureParser.parse(relPath, oldContent) : null;
        ParsedElement newRoot = newContent != null ? JavaStructureParser.parse(relPath, newContent) : null;

        return StructuralDiffBuilder.diff(oldRoot, newRoot);
    }
}
