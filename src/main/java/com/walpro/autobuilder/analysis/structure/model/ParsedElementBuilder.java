package com.walpro.autobuilder.analysis.structure.model;

import com.walpro.autobuilder.analysis.diff.model.ElementType;

import java.util.*;

public class ParsedElementBuilder {

    private String id;
    private ElementType type = ElementType.UNKNOWN;
    private String content;
    private final Map<String, Object> metadata = new HashMap<>();
    private final List<ParsedElement> children = new ArrayList<>();

    public ParsedElementBuilder id(String id) {
        this.id = id;
        return this;
    }

    public ParsedElementBuilder type(ElementType type) {
        this.type = type;
        return this;
    }

    public ParsedElementBuilder content(String content) {
        this.content = content;
        return this;
    }

    public ParsedElementBuilder putMeta(String key, Object value) {
        if (value != null) metadata.put(key, value);
        return this;
    }

    public ParsedElementBuilder addChild(ParsedElement child) {
        children.add(child);
        return this;
    }

    public ParsedElementBuilder addChildren(Collection<ParsedElement> nodes) {
        children.addAll(nodes);
        return this;
    }

    public ParsedElement build() {
        return new ParsedElement(id, type, content, Map.copyOf(metadata), List.copyOf(children));
    }
}
