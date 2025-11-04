package com.walpro.autobuilder.analysis.diff.model;

import java.util.Map;

public record ElementDescriptor(
    String id,                  // e.g. "com.example.Hello.sayHello" or "config/database/url"
    ElementType type,           // type of element
    String content,             // raw content (e.g., method source, config block)
    Map<String, Object> metadata // optional: lineNumber, modifiers, etc.
) {}
