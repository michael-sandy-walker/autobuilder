# Port review: stuck-questions

- **Ticket toy case:** `data/stuck-questions` (seed: change `StuckQuestions`, specification `StuckQuestionsSpecTest`)
- **Loop run:** `2026-09-26_15-03-38` (`~/.autobuilder/run-archive/2026-09-26_15-03-38/artifact-src.tar.gz`)
- **Port:** commit `fbd037b1`
- **State:** `ported-unreviewed` -- the operator decides; see Decision at the end.
- **Reference:** written blind in `/home/anugeshtu/.autobuilder/blind/stuck-questions-2026-09-27_19-02-01` from the pristine module, the seed and the spec only. The writer was instructed not to read anything outside that directory; this machine does not enforce it.

The four versions are in `origin/`, `loop/`, `reference/`, `port/` beside this file.

## Measured (no opinion)

| version | suite | spec | lines | +added / -removed vs origin | origin comment lines removed | new methods | new literal tables |
|---|---|---|---|---|---|---|---|
| origin | 29 run, 25 failed | 1/26 | 56 | +0 / -0 | 0 | - | 0 |
| loop | 29 run, 0 failed | 26/26 | 225 | +170 / -1 | 0 | extractConstructors, extractOverloads, extractTypeFromMembersSignature, findSourceForType | 0 |
| reference | 29 run, 0 failed | 26/26 | 152 | +97 / -1 | 0 | constructors, declarations, members, overloads, signature, typeNamed | 0 |
| port | 29 run, 0 failed | 26/26 | 237 | +182 / -1 | 0 | extractConstructors, extractOverloads, extractTypeFromMembersSignature, findSourceForType, withoutComments | 0 |

### Behaviour beyond the specification

Every input of `design/ports/stuck-router/differential.sh`, answered by each version. Listed: the inputs where the versions do NOT all agree (50 inputs in all).

Not listed: 44 input(s) where loop, reference and port agree with each other but differ from origin (the change itself); their answers are in `<version>/differential.tsv`.

- `the build fails: T.java: constructor P in record a.P cannot be applied to given types;` (tree `explicit-record`)
  - origin: `(threw UnsupportedOperationException)`
  - loop: `CONSTRUCTOR | P | null | meant=null | [P(int x), P(int x)]`
  - reference: `CONSTRUCTOR | P | null | meant=null | [P(int x)]`
  - port: `CONSTRUCTOR | P | null | meant=null | [P(int x), P(int x)]`
- `the build fails: T.java: method put in class a.Box<T> cannot be applied to given types;` (tree `generic-box`)
  - origin: `(threw UnsupportedOperationException)`
  - loop: `OVERLOADS | Box | put | meant=null | [put(T t), put(T t, int i)]`
  - reference: `(no answer)`
  - port: `OVERLOADS | Box | put | meant=null | [put(T t), put(T t, int i)]`
- `the build fails: T.java: method m in interface a.Shape cannot be applied to given types;` (tree `iface`)
  - origin: `(threw UnsupportedOperationException)`
  - loop: `(no answer)`
  - reference: `OVERLOADS | Shape | m | meant=null | [m(int x)]`
  - port: `(no answer)`
- `the build fails: Foo has no bar()` (tree `main-test-dup`)
  - origin: `(threw UnsupportedOperationException)`
  - loop: `MEMBERS | Foo | bar | meant=null | [qux()]`
  - reference: `(no answer)`
  - port: `MEMBERS | Foo | bar | meant=null | [baz()]`
- `the build fails: T.java: method bar in class a.Foo cannot be applied to given types;` (tree `overloads-vis`)
  - origin: `(threw UnsupportedOperationException)`
  - loop: `OVERLOADS | Foo | bar | meant=null | [bar(String s)]`
  - reference: `OVERLOADS | Foo | bar | meant=null | [bar(int a), bar(String... s)]`
  - port: `OVERLOADS | Foo | bar | meant=null | [bar(String s)]`
- `the build fails: T.java: constructor Foo in record b.y.Foo cannot be applied to given types;` (tree `two-pkgs`)
  - origin: `(threw UnsupportedOperationException)`
  - loop: `CONSTRUCTOR | Foo | null | meant=null | [Foo(int n)]`
  - reference: `CONSTRUCTOR | Foo | null | meant=null | [Foo(String s)]`
  - port: `CONSTRUCTOR | Foo | null | meant=null | [Foo(int n)]`

## Diffs

### origin -> loop

```diff
--- origin/StuckQuestions.java
+++ loop/StuckQuestions.java
@@ -1,7 +1,22 @@
 package com.walpro.autobuilder.core.build;
 
+import com.github.javaparser.JavaParser;
+import com.github.javaparser.ParserConfiguration;
+import com.github.javaparser.ast.CompilationUnit;
+import com.github.javaparser.ast.Modifier;
+import com.github.javaparser.ast.body.ConstructorDeclaration;
+import com.github.javaparser.ast.body.MethodDeclaration;
+import com.github.javaparser.ast.body.RecordDeclaration;
+import com.github.javaparser.ast.body.TypeDeclaration;
+
+import java.util.ArrayList;
+import java.util.LinkedHashSet;
 import java.util.List;
 import java.util.Map;
 import java.util.Optional;
+import java.util.Set;
+import java.util.regex.Matcher;
+import java.util.regex.Pattern;
+import java.util.stream.Collectors;
 
 /**
@@ -45,4 +60,8 @@
     }
 
+    private static final Pattern MEMBERS_PATTERN = Pattern.compile("has no (\\w+)\\(");
+    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile("constructor (\\w+) in (?:record|class) ([\\w.]+)");
+    private static final Pattern OVERLOADS_PATTERN = Pattern.compile("method (\\w+) in (?:record|class) ([\\w.]+)");
+
     /**
      * The answer the project's sources give to the question {@code signature} poses, or empty.
@@ -52,5 +71,155 @@
      */
     public static Optional<Answer> answer(String signature, Map<String, String> sources) {
-        throw new UnsupportedOperationException("not implemented");
-    }
-}
+        if (signature == null || sources == null || sources.isEmpty()) {
+            return Optional.empty();
+        }
+
+        // Try MEMBERS pattern first
+        Matcher membersMatcher = MEMBERS_PATTERN.matcher(signature);
+        if (membersMatcher.find()) {
+            String memberName = membersMatcher.group(1);
+            // Extract type name from signature: "Type has no member"
+            String type = extractTypeFromMembersSignature(signature);
+            if (type != null) {
+                Optional<String> source = findSourceForType(type, sources);
+                if (source.isPresent()) {
+                    List<String> declared = DeclaredMembers.of(source.get(), type);
+                    String meant = DeclaredMembers.meantBy(memberName, declared);
+                    return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
+                }
+            }
+        }
+
+        // Try CONSTRUCTOR pattern
+        Matcher constructorMatcher = CONSTRUCTOR_PATTERN.matcher(signature);
+        if (constructorMatcher.find()) {
+            String typeName = constructorMatcher.group(1);
+            String fqcn = constructorMatcher.group(2);
+            String simpleType = fqcn.substring(fqcn.lastIndexOf('.') + 1);
+            Optional<String> source = findSourceForType(simpleType, sources);
+            if (source.isPresent()) {
+                List<String> constructors = extractConstructors(source.get(), simpleType);
+                return Optional.of(new Answer(Kind.CONSTRUCTOR, simpleType, null, constructors, null));
+            }
+        }
+
+        // Try OVERLOADS pattern
+        Matcher overloadsMatcher = OVERLOADS_PATTERN.matcher(signature);
+        if (overloadsMatcher.find()) {
+            String methodName = overloadsMatcher.group(1);
+            String fqcn = overloadsMatcher.group(2);
+            String simpleType = fqcn.substring(fqcn.lastIndexOf('.') + 1);
+            Optional<String> source = findSourceForType(simpleType, sources);
+            if (source.isPresent()) {
+                List<String> overloads = extractOverloads(source.get(), simpleType, methodName);
+                return Optional.of(new Answer(Kind.OVERLOADS, simpleType, methodName, overloads, null));
+            }
+        }
+
+        return Optional.empty();
+    }
+
+    private static String extractTypeFromMembersSignature(String signature) {
+        // Pattern: "Type has no member"
+        int hasNoIndex = signature.indexOf("has no ");
+        if (hasNoIndex < 0) return null;
+        String before = signature.substring(0, hasNoIndex).trim();
+        // The type is the last word before "has no"
+        String[] parts = before.split("\\s+");
+        if (parts.length == 0) return null;
+        return parts[parts.length - 1];
+    }
+
+    private static Optional<String> findSourceForType(String simpleType, Map<String, String> sources) {
+        for (Map.Entry<String, String> entry : sources.entrySet()) {
+            String path = entry.getKey();
+            String content = entry.getValue();
+            // Check if this file declares the type
+            if (content != null && content.contains("class " + simpleType) || 
+                content != null && content.contains("record " + simpleType) ||
+                content != null && content.contains("interface " + simpleType)) {
+                return Optional.of(content);
+            }
+        }
+        return Optional.empty();
+    }
+
+    private static List<String> extractConstructors(String source, String simpleType) {
+        if (source == null || simpleType == null) return List.of();
+        CompilationUnit cu;
+        try {
+            cu = new JavaParser(new ParserConfiguration()
+                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
+                    .parse(source).getResult().orElse(null);
+        } catch (RuntimeException e) {
+            return List.of();
+        }
+        if (cu == null) return List.of();
+        
+        TypeDeclaration<?> found = null;
+        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
+            if (t.getNameAsString().equals(simpleType)) {
+                found = t;
+                break;
+            }
+        }
+        if (found == null) return List.of();
+        
+        List<String> constructors = new ArrayList<>();
+        // For records, the canonical constructor comes first
+        if (found instanceof RecordDeclaration r) {
+            String params = r.getParameters().stream()
+                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
+                    .collect(Collectors.joining(", "));
+            constructors.add(simpleType + "(" + params + ")");
+        }
+        
+        // Add explicit constructors
+        for (ConstructorDeclaration c : found.getConstructors()) {
+            if (c.isPrivate()) continue;
+            String params = c.getParameters().stream()
+                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
+                    .collect(Collectors.joining(", "));
+            constructors.add(simpleType + "(" + params + ")");
+        }
+        
+        return constructors;
+    }
+
+    private static List<String> extractOverloads(String source, String simpleType, String methodName) {
+        if (source == null || simpleType == null || methodName == null) return List.of();
+        CompilationUnit cu;
+        try {
+            cu = new JavaParser(new ParserConfiguration()
+                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
+                    .parse(source).getResult().orElse(null);
+        } catch (RuntimeException e) {
+            return List.of();
+        }
+        if (cu == null) return List.of();
+        
+        TypeDeclaration<?> found = null;
+        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
+            if (t.getNameAsString().equals(simpleType)) {
+                found = t;
+                break;
+            }
+        }
+        if (found == null) return List.of();
+        
+        List<String> overloads = new ArrayList<>();
+        boolean isInterface = found.isClassOrInterfaceDeclaration()
+                && found.asClassOrInterfaceDeclaration().isInterface();
+        
+        for (MethodDeclaration m : found.getMethods()) {
+            if (!m.getNameAsString().equals(methodName)) continue;
+            if (!(isInterface || m.hasModifier(Modifier.Keyword.PUBLIC))) continue;
+            String params = m.getParameters().stream()
+                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
+                    .collect(Collectors.joining(", "));
+            overloads.add(methodName + "(" + params + ")");
+        }
+        
+        return overloads;
+    }
+}```
### origin -> reference

```diff
--- origin/StuckQuestions.java
+++ reference/StuckQuestions.java
@@ -1,7 +1,23 @@
 package com.walpro.autobuilder.core.build;
 
+import com.github.javaparser.JavaParser;
+import com.github.javaparser.ParserConfiguration;
+import com.github.javaparser.ast.CompilationUnit;
+import com.github.javaparser.ast.NodeList;
+import com.github.javaparser.ast.body.CallableDeclaration;
+import com.github.javaparser.ast.body.Parameter;
+import com.github.javaparser.ast.body.RecordDeclaration;
+import com.github.javaparser.ast.body.TypeDeclaration;
+
+import java.util.ArrayList;
+import java.util.LinkedHashSet;
 import java.util.List;
 import java.util.Map;
 import java.util.Optional;
+import java.util.Set;
+import java.util.function.Predicate;
+import java.util.regex.Matcher;
+import java.util.regex.Pattern;
+import java.util.stream.Collectors;
 
 /**
@@ -42,4 +58,13 @@
     }
 
+    /** "Foo has no bar(...)": the grind detector's own wording for a member a type may not declare. */
+    private static final Pattern MISSING_MEMBER = Pattern.compile("\\b(\\w+) has no (\\w+)\\(");
+    /** javac: "constructor Foo in record a.b.Foo cannot be applied to given types". */
+    private static final Pattern CONSTRUCTOR_MISMATCH =
+            Pattern.compile("\\bconstructor \\w+ in \\w+ ([\\w.]+) cannot be applied to given types");
+    /** javac: "method bar in class a.b.Foo cannot be applied to given types". */
+    private static final Pattern METHOD_MISMATCH =
+            Pattern.compile("\\bmethod (\\w+) in \\w+ ([\\w.]+) cannot be applied to given types");
+
     private StuckQuestions() {
     }
@@ -52,5 +77,76 @@
      */
     public static Optional<Answer> answer(String signature, Map<String, String> sources) {
-        throw new UnsupportedOperationException("not implemented");
+        if (signature == null || sources == null || sources.isEmpty()) return Optional.empty();
+        Matcher m = MISSING_MEMBER.matcher(signature);
+        if (m.find()) return members(m.group(1), m.group(2), sources);
+        m = CONSTRUCTOR_MISMATCH.matcher(signature);
+        if (m.find()) return constructors(m.group(1), sources);
+        m = METHOD_MISMATCH.matcher(signature);
+        if (m.find()) return overloads(m.group(2), m.group(1), sources);
+        return Optional.empty();
+    }
+
+    private static Optional<Answer> members(String simpleName, String member, Map<String, String> sources) {
+        // The signature names the type by its simple name only: answer when exactly one source declares it.
+        List<String> declaring = sources.values().stream()
+                .filter(src -> !declarations(src, t -> t.getNameAsString().equals(simpleName)).isEmpty())
+                .toList();
+        if (declaring.size() != 1) return Optional.empty();
+        List<String> declared = DeclaredMembers.of(declaring.get(0), simpleName);
+        return Optional.of(new Answer(Kind.MEMBERS, simpleName, member, declared,
+                DeclaredMembers.meantBy(member, declared)));
+    }
+
+    private static Optional<Answer> constructors(String qualifiedName, Map<String, String> sources) {
+        return typeNamed(qualifiedName, sources).map(type -> {
+            Set<String> declared = new LinkedHashSet<>();
+            if (type instanceof RecordDeclaration r) {
+                declared.add(signature(r.getNameAsString(), r.getParameters()));
+            }
+            type.getConstructors().forEach(c -> declared.add(signature(c)));
+            return new Answer(Kind.CONSTRUCTOR, type.getNameAsString(), null, new ArrayList<>(declared), null);
+        });
+    }
+
+    private static Optional<Answer> overloads(String qualifiedName, String method, Map<String, String> sources) {
+        return typeNamed(qualifiedName, sources).map(type -> new Answer(Kind.OVERLOADS, type.getNameAsString(),
+                method, type.getMethodsByName(method).stream().map(StuckQuestions::signature).toList(), null));
+    }
+
+    /** The one type whose fully qualified name javac reported, from whichever source declares it. */
+    private static Optional<TypeDeclaration<?>> typeNamed(String qualifiedName, Map<String, String> sources) {
+        Predicate<TypeDeclaration<?>> named = t -> t.getFullyQualifiedName().map(qualifiedName::equals).orElse(false);
+        return sources.values().stream().flatMap(src -> declarations(src, named).stream()).findFirst();
+    }
+
+    /** The types {@code source} declares, nested ones included, that satisfy {@code wanted}; empty when unparseable. */
+    private static List<TypeDeclaration<?>> declarations(String source, Predicate<TypeDeclaration<?>> wanted) {
+        if (source == null) return List.of();
+        CompilationUnit cu;
+        try {
+            // Records need a language level the parser's default does not enable.
+            cu = new JavaParser(new ParserConfiguration()
+                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
+                    .parse(source).getResult().orElse(null);
+        } catch (RuntimeException e) {
+            return List.of();
+        }
+        if (cu == null) return List.of();
+        List<TypeDeclaration<?>> found = new ArrayList<>();
+        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
+            if (wanted.test(t)) found.add(t);
+        }
+        return found;
+    }
+
+    private static String signature(CallableDeclaration<?> callable) {
+        return signature(callable.getNameAsString(), callable.getParameters());
+    }
+
+    /** {@code name(ParamType name, ...)}, the types as written in the source. */
+    private static String signature(String name, NodeList<Parameter> parameters) {
+        return name + parameters.stream()
+                .map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : "") + " " + p.getNameAsString())
+                .collect(Collectors.joining(", ", "(", ")"));
     }
 }
```
### origin -> port

```diff
--- origin/StuckQuestions.java
+++ port/StuckQuestions.java
@@ -1,7 +1,20 @@
 package com.walpro.autobuilder.core.build;
 
+import com.github.javaparser.JavaParser;
+import com.github.javaparser.ParserConfiguration;
+import com.github.javaparser.ast.CompilationUnit;
+import com.github.javaparser.ast.Modifier;
+import com.github.javaparser.ast.body.ConstructorDeclaration;
+import com.github.javaparser.ast.body.MethodDeclaration;
+import com.github.javaparser.ast.body.RecordDeclaration;
+import com.github.javaparser.ast.body.TypeDeclaration;
+
+import java.util.ArrayList;
 import java.util.List;
 import java.util.Map;
 import java.util.Optional;
+import java.util.regex.Matcher;
+import java.util.regex.Pattern;
+import java.util.stream.Collectors;
 
 /**
@@ -45,4 +58,8 @@
     }
 
+    private static final Pattern MEMBERS_PATTERN = Pattern.compile("has no (\\w+)\\(");
+    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile("constructor (\\w+) in (?:record|class) ([\\w.]+)");
+    private static final Pattern OVERLOADS_PATTERN = Pattern.compile("method (\\w+) in (?:record|class) ([\\w.]+)");
+
     /**
      * The answer the project's sources give to the question {@code signature} poses, or empty.
@@ -52,5 +69,169 @@
      */
     public static Optional<Answer> answer(String signature, Map<String, String> sources) {
-        throw new UnsupportedOperationException("not implemented");
+        if (signature == null || sources == null || sources.isEmpty()) {
+            return Optional.empty();
+        }
+
+        // Try MEMBERS pattern first
+        Matcher membersMatcher = MEMBERS_PATTERN.matcher(signature);
+        if (membersMatcher.find()) {
+            String memberName = membersMatcher.group(1);
+            // Extract type name from signature: "Type has no member"
+            String type = extractTypeFromMembersSignature(signature);
+            if (type != null) {
+                Optional<String> source = findSourceForType(type, sources);
+                if (source.isPresent()) {
+                    List<String> declared = DeclaredMembers.of(source.get(), type);
+                    String meant = DeclaredMembers.meantBy(memberName, declared);
+                    return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
+                }
+            }
+        }
+
+        // Try CONSTRUCTOR pattern
+        Matcher constructorMatcher = CONSTRUCTOR_PATTERN.matcher(signature);
+        if (constructorMatcher.find()) {
+            String fqcn = constructorMatcher.group(2);
+            String simpleType = fqcn.substring(fqcn.lastIndexOf('.') + 1);
+            Optional<String> source = findSourceForType(simpleType, sources);
+            if (source.isPresent()) {
+                List<String> constructors = extractConstructors(source.get(), simpleType);
+                return Optional.of(new Answer(Kind.CONSTRUCTOR, simpleType, null, constructors, null));
+            }
+        }
+
+        // Try OVERLOADS pattern
+        Matcher overloadsMatcher = OVERLOADS_PATTERN.matcher(signature);
+        if (overloadsMatcher.find()) {
+            String methodName = overloadsMatcher.group(1);
+            String fqcn = overloadsMatcher.group(2);
+            String simpleType = fqcn.substring(fqcn.lastIndexOf('.') + 1);
+            Optional<String> source = findSourceForType(simpleType, sources);
+            if (source.isPresent()) {
+                List<String> overloads = extractOverloads(source.get(), simpleType, methodName);
+                return Optional.of(new Answer(Kind.OVERLOADS, simpleType, methodName, overloads, null));
+            }
+        }
+
+        return Optional.empty();
+    }
+
+    private static String extractTypeFromMembersSignature(String signature) {
+        // Pattern: "Type has no member"
+        int hasNoIndex = signature.indexOf("has no ");
+        if (hasNoIndex < 0) return null;
+        String before = signature.substring(0, hasNoIndex).trim();
+        // The type is the last word before "has no"
+        String[] parts = before.split("\\s+");
+        if (parts.length == 0) return null;
+        return parts[parts.length - 1];
+    }
+
+    /**
+     * The source that DECLARES {@code simpleType}: the file named after it first, then any file with a
+     * real declaration of that name. Port review (2026-09-26): the loop's version matched
+     * {@code "class " + simpleType} as a substring, so {@code ParsedElement} also matched
+     * {@code class ParsedElementBuilder} and a name in a comment -- right on the archive only because
+     * the file map happened to list the named file first.
+     */
+    private static Optional<String> findSourceForType(String simpleType, Map<String, String> sources) {
+        Pattern declaration = Pattern.compile("\\b(?:class|record|interface|enum)\\s+" + Pattern.quote(simpleType) + "\\b");
+        for (Map.Entry<String, String> entry : sources.entrySet()) {
+            String path = entry.getKey().replace('\\', '/');
+            if ((path.endsWith("/" + simpleType + ".java") || path.equals(simpleType + ".java"))
+                    && entry.getValue() != null && declaration.matcher(withoutComments(entry.getValue())).find()) {
+                return Optional.of(entry.getValue());
+            }
+        }
+        for (Map.Entry<String, String> entry : sources.entrySet()) {
+            String content = entry.getValue();
+            if (content != null && entry.getKey().endsWith(".java") && declaration.matcher(withoutComments(content)).find()) {
+                return Optional.of(content);
+            }
+        }
+        return Optional.empty();
+    }
+
+    private static String withoutComments(String source) {
+        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
+    }
+
+    private static List<String> extractConstructors(String source, String simpleType) {
+        if (source == null || simpleType == null) return List.of();
+        CompilationUnit cu;
+        try {
+            cu = new JavaParser(new ParserConfiguration()
+                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
+                    .parse(source).getResult().orElse(null);
+        } catch (RuntimeException e) {
+            return List.of();
+        }
+        if (cu == null) return List.of();
+        
+        TypeDeclaration<?> found = null;
+        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
+            if (t.getNameAsString().equals(simpleType)) {
+                found = t;
+                break;
+            }
+        }
+        if (found == null) return List.of();
+        
+        List<String> constructors = new ArrayList<>();
+        // For records, the canonical constructor comes first
+        if (found instanceof RecordDeclaration r) {
+            String params = r.getParameters().stream()
+                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
+                    .collect(Collectors.joining(", "));
+            constructors.add(simpleType + "(" + params + ")");
+        }
+        
+        // Add explicit constructors
+        for (ConstructorDeclaration c : found.getConstructors()) {
+            if (c.isPrivate()) continue;
+            String params = c.getParameters().stream()
+                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
+                    .collect(Collectors.joining(", "));
+            constructors.add(simpleType + "(" + params + ")");
+        }
+        
+        return constructors;
+    }
+
+    private static List<String> extractOverloads(String source, String simpleType, String methodName) {
+        if (source == null || simpleType == null || methodName == null) return List.of();
+        CompilationUnit cu;
+        try {
+            cu = new JavaParser(new ParserConfiguration()
+                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
+                    .parse(source).getResult().orElse(null);
+        } catch (RuntimeException e) {
+            return List.of();
+        }
+        if (cu == null) return List.of();
+        
+        TypeDeclaration<?> found = null;
+        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
+            if (t.getNameAsString().equals(simpleType)) {
+                found = t;
+                break;
+            }
+        }
+        if (found == null) return List.of();
+        
+        List<String> overloads = new ArrayList<>();
+        boolean isInterface = found.isClassOrInterfaceDeclaration()
+                && found.asClassOrInterfaceDeclaration().isInterface();
+        
+        for (MethodDeclaration m : found.getMethods()) {
+            if (!m.getNameAsString().equals(methodName)) continue;
+            if (!(isInterface || m.hasModifier(Modifier.Keyword.PUBLIC))) continue;
+            String params = m.getParameters().stream()
+                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
+                    .collect(Collectors.joining(", "));
+            overloads.add(methodName + "(" + params + ")");
+        }
+        
+        return overloads;
     }
 }
```
### loop -> port (what the port review changed in the loop's code)

```diff
--- loop/StuckQuestions.java
+++ port/StuckQuestions.java
@@ -11,9 +11,7 @@
 
 import java.util.ArrayList;
-import java.util.LinkedHashSet;
 import java.util.List;
 import java.util.Map;
 import java.util.Optional;
-import java.util.Set;
 import java.util.regex.Matcher;
 import java.util.regex.Pattern;
@@ -94,5 +92,4 @@
         Matcher constructorMatcher = CONSTRUCTOR_PATTERN.matcher(signature);
         if (constructorMatcher.find()) {
-            String typeName = constructorMatcher.group(1);
             String fqcn = constructorMatcher.group(2);
             String simpleType = fqcn.substring(fqcn.lastIndexOf('.') + 1);
@@ -131,16 +128,31 @@
     }
 
+    /**
+     * The source that DECLARES {@code simpleType}: the file named after it first, then any file with a
+     * real declaration of that name. Port review (2026-09-26): the loop's version matched
+     * {@code "class " + simpleType} as a substring, so {@code ParsedElement} also matched
+     * {@code class ParsedElementBuilder} and a name in a comment -- right on the archive only because
+     * the file map happened to list the named file first.
+     */
     private static Optional<String> findSourceForType(String simpleType, Map<String, String> sources) {
+        Pattern declaration = Pattern.compile("\\b(?:class|record|interface|enum)\\s+" + Pattern.quote(simpleType) + "\\b");
         for (Map.Entry<String, String> entry : sources.entrySet()) {
-            String path = entry.getKey();
+            String path = entry.getKey().replace('\\', '/');
+            if ((path.endsWith("/" + simpleType + ".java") || path.equals(simpleType + ".java"))
+                    && entry.getValue() != null && declaration.matcher(withoutComments(entry.getValue())).find()) {
+                return Optional.of(entry.getValue());
+            }
+        }
+        for (Map.Entry<String, String> entry : sources.entrySet()) {
             String content = entry.getValue();
-            // Check if this file declares the type
-            if (content != null && content.contains("class " + simpleType) || 
-                content != null && content.contains("record " + simpleType) ||
-                content != null && content.contains("interface " + simpleType)) {
+            if (content != null && entry.getKey().endsWith(".java") && declaration.matcher(withoutComments(content)).find()) {
                 return Optional.of(content);
             }
         }
         return Optional.empty();
+    }
+
+    private static String withoutComments(String source) {
+        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
     }
 
@@ -223,3 +235,3 @@
         return overloads;
     }
-}+}
```

## Assessment: better / worse

In `ASSESSMENT.md` beside this file, written by an independent comparer (a fresh agent) that had no hand in the loop's code, the reference or the port. An opinion, kept apart from the measurements above.

## Decision (the operator)

- [ ] approve the port as it is
- [ ] approve with changes: ...
- [ ] decline (revert or do not port)
