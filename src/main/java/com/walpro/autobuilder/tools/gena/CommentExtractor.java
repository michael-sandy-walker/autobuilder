package com.walpro.autobuilder.tools.gena;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.JavadocComment;

public class CommentExtractor {

    public static String getCleanComment(Node node) {
        return node.getComment()
                .filter(JavadocComment.class::isInstance)
                .map(c -> ((JavadocComment) c).parse().getDescription().toText())
                .map(String::trim)
                .orElse(null);
    }
}
