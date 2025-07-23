package com.walpro.autobuilder.parser.model;

import com.walpro.autobuilder.diff.model.ElementType;

import java.util.List;
import java.util.Map;

public record ParsedElement(
    String id,                    // e.g., com.example.Hello.sayHello
    ElementType type,            // e.g., CLASS, METHOD
    String content,              // raw source code of this element
    Map<String, Object> metadata,// e.g., line number, modifiers
    List<ParsedElement> children // nested methods, fields, etc.
) {}
