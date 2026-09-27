# Port review: receiver-questions

- **Ticket toy case:** `data/receiver-questions` (seed: change `StuckQuestions`, specification `ReceiverQuestionsSpecTest`)
- **Loop run:** `2026-09-26_21-50-02` (`~/.autobuilder/run-archive/2026-09-26_21-50-02/artifact-src.tar.gz`)
- **Port:** commit `213af020`
- **State:** `ported-unreviewed` -- the operator decides; see Decision at the end.
- **Reference:** written blind in `/home/anugeshtu/.autobuilder/blind/receiver-questions-2026-09-27_07-22-02` from the pristine module, the seed and the spec only. The writer was instructed not to read anything outside that directory; this machine does not enforce it.

The four versions are in `origin/`, `loop/`, `reference/`, `port/` beside this file.

## Measured (no opinion)

| version | suite | spec | lines | +added / -removed vs origin | origin comment lines removed | new methods | new literal tables |
|---|---|---|---|---|---|---|---|
| origin | 48 run, 11 failed | 2/13 | 358 | +0 / -0 | 0 | - | 0 |
| loop | 48 run, 0 failed | 13/13 | 454 | +117 / -21 | 15 | answerJdkReceiver, findJdkClass, findMethodDeclarationInSources, jdkPublicMethods | 1 |
| reference | 48 run, 0 failed | 13/13 | 400 | +47 / -5 | 1 | jdkReceiver, jdkTypes | 0 |
| port | 48 run, 0 failed | 13/13 | 405 | +47 / -0 | 0 | answerJdkReceiver | 0 |
| proposed | 48 run, 0 failed | 13/13 | 489 | +145 / -14 | 4 | importedOnDemand, load, platform, receiverAnswer, resolveAgainst | 0 |

### Behaviour beyond the specification

Every input of `design/ports/stuck-router/differential.sh`, answered by each version. Listed: the inputs where the versions do NOT all agree (44 inputs in all).

Not listed: 10 input(s) where loop, reference and port agree with each other but differ from origin (the change itself); their answers are in `<version>/differential.tsv`.

- `the build fails: Base64 has no decodeBase64(String)` (tree `imports-foreign`)
  - origin: `(no answer)`
  - loop: `MEMBERS | Base64 | decodeBase64 | meant=null | [equals(Object), getClass(), getDecoder(), getEncoder(), getMimeDecoder(), getMimeEncoder(), getMimeEncoder(int, byte[]), getUrlDecoder(), getUrlEncoder(), hashCode(), notif`
  - reference: `MEMBERS | Base64 | decodeBase64 | meant=null | [equals(Object), getClass(), getDecoder(), getEncoder(), getMimeDecoder(), getMimeEncoder(), getMimeEncoder(int, byte[]), getUrlDecoder(), getUrlEncoder(), hashCode(), notif`
  - port: `MEMBERS | Base64 | decodeBase64 | meant=null | [equals(Object), getClass(), getDecoder(), getEncoder(), getMimeDecoder(), getMimeEncoder(), getMimeEncoder(int, byte[]), getUrlDecoder(), getUrlEncoder(), hashCode(), notif`
  - proposed: `(no answer)`
- `the build fails: Module has no configure()` (tree `imports-foreign`)
  - origin: `(no answer)`
  - loop: `MEMBERS | Module | configure | meant=null | [addExports(String, Module), addOpens(String, Module), addReads(Module), addUses(Class), canRead(Module), canUse(Class), equals(Object), getAnnotation(Class), getAnnotations(),`
  - reference: `MEMBERS | Module | configure | meant=null | [addExports(String, Module), addOpens(String, Module), addReads(Module), addUses(Class), canRead(Module), canUse(Class), equals(Object), getAnnotation(Class), getAnnotations(),`
  - port: `MEMBERS | Module | configure | meant=null | [addExports(String, Module), addOpens(String, Module), addReads(Module), addUses(Class), canRead(Module), canUse(Class), equals(Object), getAnnotation(Class), getAnnotations(),`
  - proposed: `(no answer)`
- `the build fails: List has no getFirstItem()` (tree `imports-util`)
  - origin: `(no answer)`
  - loop: `MEMBERS | List | getFirstItem | meant=null | [add(Object), add(int, Object), addAll(Collection), addAll(int, Collection), addFirst(Object), addLast(Object), clear(), contains(Object), containsAll(Collection), copyOf(Coll`
  - reference: `MEMBERS | List | getFirstItem | meant=null | [action(Event, Object), add(Object), add(PopupMenu), add(String), add(String, int), add(int, Object), addActionListener(ActionListener), addAll(Collection), addAll(int, Collec`
  - port: `(no answer)`
  - proposed: `MEMBERS | List | getFirstItem | meant=null | [add(Object), add(int, Object), addAll(Collection), addAll(int, Collection), addFirst(Object), addLast(Object), clear(), contains(Object), containsAll(Collection), copyOf(Coll`
- `the build fails: List has no getFirstItem()` (tree `none`)
  - origin: `(no answer)`
  - loop: `MEMBERS | List | getFirstItem | meant=null | [add(Object), add(int, Object), addAll(Collection), addAll(int, Collection), addFirst(Object), addLast(Object), clear(), contains(Object), containsAll(Collection), copyOf(Coll`
  - reference: `MEMBERS | List | getFirstItem | meant=null | [action(Event, Object), add(Object), add(PopupMenu), add(String), add(String, int), add(int, Object), addActionListener(ActionListener), addAll(Collection), addAll(int, Collec`
  - port: `(no answer)`
  - proposed: `(no answer)`
- `the build fails: Quill.java: cannot find symbol method indexOf(Quill) in variable active of type java.util.Map<java.lang.String,org.ink.Quill>` (tree `none`)
  - origin: `(no answer)`
  - loop: `(no answer)`
  - reference: `MEMBERS | Map | indexOf | meant=null | [clear(), compute(Object, BiFunction), computeIfAbsent(Object, Function), computeIfPresent(Object, BiFunction), containsKey(Object), containsValue(Object), copyOf(Map), entry(Object`
  - port: `(no answer)`
  - proposed: `MEMBERS | Map | indexOf | meant=null | [clear(), compute(Object, BiFunction), computeIfAbsent(Object, Function), computeIfPresent(Object, BiFunction), containsKey(Object), containsValue(Object), copyOf(Map), entry(Object`
- `the build fails: String has no getLength()` (tree `none`)
  - origin: `(no answer)`
  - loop: `MEMBERS | String | getLength | meant=null | [charAt(int), chars(), codePointAt(int), codePointBefore(int), codePointCount(int, int), codePoints(), compareTo(Object), compareTo(String), compareToIgnoreCase(String), concat`
  - reference: `MEMBERS | String | getLength | meant=null | [charAt(int), chars(), codePointAt(int), codePointBefore(int), codePointCount(int, int), codePoints(), compareTo(String), compareToIgnoreCase(String), concat(String), contains(`
  - port: `MEMBERS | String | getLength | meant=null | [charAt(int), chars(), codePointAt(int), codePointBefore(int), codePointCount(int, int), codePoints(), compareTo(Object), compareTo(String), compareToIgnoreCase(String), concat`
  - proposed: `MEMBERS | String | getLength | meant=null | [charAt(int), chars(), codePointAt(int), codePointBefore(int), codePointCount(int, int), codePoints(), compareTo(String), compareToIgnoreCase(String), concat(String), contains(`
- `the build fails: Clock.java: cannot find symbol method decode(String) in record component code of type Base64` (tree `shadow`)
  - origin: `(no answer)`
  - loop: `MEMBERS | Base64 | decode | meant=null | [equals(Object), getClass(), getDecoder(), getEncoder(), getMimeDecoder(), getMimeEncoder(), getMimeEncoder(int, byte[]), getUrlDecoder(), getUrlEncoder(), hashCode(), notify(), n`
  - reference: `(no answer)`
  - port: `MEMBERS | Base64 | decode | meant=null | [equals(Object), getClass(), getDecoder(), getEncoder(), getMimeDecoder(), getMimeEncoder(), getMimeEncoder(int, byte[]), getUrlDecoder(), getUrlEncoder(), hashCode(), notify(), n`
  - proposed: `(no answer)`
- `the build fails: Clock.java: cannot find symbol method stop() in record component t of type Timer` (tree `shadow`)
  - origin: `(no answer)`
  - loop: `MEMBERS | Timer | stop | meant=null | [cancel(), equals(Object), getClass(), hashCode(), notify(), notifyAll(), purge(), schedule(TimerTask, Date), schedule(TimerTask, Date, long), schedule(TimerTask, long), schedule(Tim`
  - reference: `(no answer)`
  - port: `(no answer)`
  - proposed: `(no answer)`

## Diffs

### origin -> loop

```diff
--- origin/StuckQuestions.java
+++ loop/StuckQuestions.java
@@ -4,5 +4,4 @@
 import com.github.javaparser.ParserConfiguration;
 import com.github.javaparser.ast.CompilationUnit;
-import com.github.javaparser.ast.Modifier;
 import com.github.javaparser.ast.body.ConstructorDeclaration;
 import com.github.javaparser.ast.body.MethodDeclaration;
@@ -10,8 +9,10 @@
 import com.github.javaparser.ast.body.TypeDeclaration;
 
+import java.lang.reflect.Method;
 import java.util.ArrayList;
 import java.util.List;
 import java.util.Map;
 import java.util.Optional;
+import java.util.TreeSet;
 import java.util.regex.Matcher;
 import java.util.regex.Pattern;
@@ -68,9 +69,6 @@
 
     private static final Pattern MEMBERS_PATTERN = Pattern.compile("has no (\\w+)\\(");
-    /**
-     * javac's own wording ("constructor X in record a.b.X"), or the grind detector's for "no suitable
-     * constructor found" ("X has no constructor X(args)") -- the second was not recognised until slice B
-     * put the router in the loop, where the detector's wording is what it is asked.
-     */
+    private static final Pattern RECEIVER_METHOD_PATTERN = Pattern.compile(
+            "cannot find symbol method (\\w+)\\([^)]*\\) in record component \\w+ of type ([\\w.$]+(?:<[^>]*>)?)");
     private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile(
             "constructor (\\w+) in (?:record|class) ([\\w.]+)|has no constructor (\\w+)\\(");
@@ -90,9 +88,17 @@
         }
 
-        // Try MEMBERS pattern first
+        // Try RECEIVER_METHOD pattern first (cannot find symbol method X in record component Y of type Z)
+        Matcher receiverMatcher = RECEIVER_METHOD_PATTERN.matcher(signature);
+        if (receiverMatcher.find()) {
+            String memberName = receiverMatcher.group(1);
+            String rawType = receiverMatcher.group(2);
+            String type = rawType.contains("<") ? rawType.substring(0, rawType.indexOf('<')) : rawType;
+            return answerJdkReceiver(type, memberName, sources);
+        }
+
+        // Try MEMBERS pattern
         Matcher membersMatcher = MEMBERS_PATTERN.matcher(signature);
         if (membersMatcher.find()) {
             String memberName = membersMatcher.group(1);
-            // Extract type name from signature: "Type has no member"
             String type = extractTypeFromMembersSignature(signature);
             if (type != null) {
@@ -103,4 +109,5 @@
                     return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
                 }
+                return answerJdkReceiver(type, memberName, sources);
             }
         }
@@ -144,4 +151,17 @@
         }
 
+        return Optional.empty();
+    }
+
+    /**
+     * Answer a receiver question: the type is not in the sources, so check if it's a JDK class.
+     */
+    private static Optional<Answer> answerJdkReceiver(String type, String memberName, Map<String, String> sources) {
+        Optional<Class<?>> jdkClass = findJdkClass(type);
+        if (jdkClass.isPresent()) {
+            List<String> declared = jdkPublicMethods(jdkClass.get());
+            String meant = findMethodDeclarationInSources(memberName, sources);
+            return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
+        }
         return Optional.empty();
     }
@@ -194,8 +214,8 @@
     }
 
-    private static String visibility(com.github.javaparser.ast.NodeList<Modifier> modifiers) {
-        for (Modifier m : modifiers) {
-            if (m.getKeyword() == Modifier.Keyword.PRIVATE) return "private ";
-            if (m.getKeyword() == Modifier.Keyword.PROTECTED) return "protected ";
+    private static String visibility(com.github.javaparser.ast.NodeList<com.github.javaparser.ast.Modifier> modifiers) {
+        for (com.github.javaparser.ast.Modifier m : modifiers) {
+            if (m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.PRIVATE) return "private ";
+            if (m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.PROTECTED) return "protected ";
         }
         return "";
@@ -230,10 +250,91 @@
     }
 
+    /**
+     * Find a JDK class by simple name, searching common JDK packages first, then falling back to
+     * a full module-system scan so that any exported {@code java.*} package is covered.
+     */
+    private static Optional<Class<?>> findJdkClass(String simpleName) {
+        String[] commonPackages = {
+                "java.lang", "java.util", "java.awt", "java.awt.image", "java.awt.peer",
+                "java.io", "java.net", "java.nio", "java.sql", "java.text", "java.time",
+                "javax.swing", "javax.swing.text", "javax.swing.event"
+        };
+        for (String pkg : commonPackages) {
+            try {
+                Class<?> c = Class.forName(pkg + "." + simpleName, false, ClassLoader.getPlatformClassLoader());
+                if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
+                    return Optional.of(c);
+                }
+            } catch (ClassNotFoundException | LinkageError ignored) {
+                // not in this package
+            }
+        }
+        // Fallback: scan all exported packages of java.* modules
+        for (Module module : ModuleLayer.boot().modules()) {
+            if (!module.getName().startsWith("java.")) continue;
+            for (String pkg : module.getPackages()) {
+                if (!module.isExported(pkg)) continue;
+                try {
+                    Class<?> c = Class.forName(pkg + "." + simpleName, false, ClassLoader.getPlatformClassLoader());
+                    if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
+                        return Optional.of(c);
+                    }
+                } catch (ClassNotFoundException | LinkageError ignored) {
+                    // not in this package
+                }
+            }
+        }
+        return Optional.empty();
+    }
+
+    /**
+     * List public methods of a JDK class as name(ParamSimpleType, ...), own and inherited.
+     */
+    private static List<String> jdkPublicMethods(Class<?> clazz) {
+        TreeSet<String> methods = new TreeSet<>();
+        for (Method m : clazz.getMethods()) {
+            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
+                String params = java.util.Arrays.stream(m.getParameterTypes())
+                        .map(Class::getSimpleName)
+                        .collect(Collectors.joining(", "));
+                methods.add(m.getName() + "(" + params + ")");
+            }
+        }
+        return new ArrayList<>(methods);
+    }
+
+    /**
+     * Find the first declaration of a method with the given name in the project sources,
+     * formatted as Type.name(ParamTypes).
+     */
+    private static String findMethodDeclarationInSources(String methodName, Map<String, String> sources) {
+        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
+        for (Map.Entry<String, String> entry : new java.util.TreeMap<>(sources).entrySet()) {
+            if (!entry.getKey().endsWith(".java") || entry.getValue() == null) continue;
+            CompilationUnit cu;
+            try {
+                cu = parser.parse(entry.getValue()).getResult().orElse(null);
+            } catch (RuntimeException e) {
+                continue;
+            }
+            if (cu == null) continue;
+            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
+                for (MethodDeclaration m : t.getMethods()) {
+                    if (m.getNameAsString().equals(methodName)) {
+                        String params = m.getParameters().stream()
+                                .map(p -> p.getType().asString())
+                                .collect(Collectors.joining(", "));
+                        return qualifiedName(t) + "." + methodName + "(" + params + ")";
+                    }
+                }
+            }
+        }
+        return null;
+    }
+
     private static String extractTypeFromMembersSignature(String signature) {
-        // Pattern: "Type has no member"
         int hasNoIndex = signature.indexOf("has no ");
         if (hasNoIndex < 0) return null;
         String before = signature.substring(0, hasNoIndex).trim();
-        // The type is the last word before "has no"
         String[] parts = before.split("\\s+");
         if (parts.length == 0) return null;
@@ -243,8 +344,5 @@
     /**
      * The source that DECLARES {@code simpleType}: the file named after it first, then any file with a
-     * real declaration of that name. Port review (2026-09-26): the loop's version matched
-     * {@code "class " + simpleType} as a substring, so {@code ParsedElement} also matched
-     * {@code class ParsedElementBuilder} and a name in a comment -- right on the archive only because
-     * the file map happened to list the named file first.
+     * real declaration of that name.
      */
     private static Optional<String> findSourceForType(String simpleType, Map<String, String> sources) {
@@ -300,5 +398,4 @@
         
         List<String> constructors = new ArrayList<>();
-        // For records, the canonical constructor comes first
         if (found instanceof RecordDeclaration r) {
             String params = r.getParameters().stream()
@@ -308,5 +405,4 @@
         }
         
-        // Add explicit constructors
         for (ConstructorDeclaration c : found.getConstructors()) {
             if (c.isPrivate()) continue;
@@ -347,5 +443,5 @@
         for (MethodDeclaration m : found.getMethods()) {
             if (!m.getNameAsString().equals(methodName)) continue;
-            if (!(isInterface || m.hasModifier(Modifier.Keyword.PUBLIC))) continue;
+            if (!(isInterface || m.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PUBLIC))) continue;
             String params = m.getParameters().stream()
                     .map(p -> p.getType().asString() + " " + p.getNameAsString())
@@ -356,3 +452,3 @@
         return overloads;
     }
-}
+}```
### origin -> reference

```diff
--- origin/StuckQuestions.java
+++ reference/StuckQuestions.java
@@ -48,4 +48,9 @@
      * The answer, read from the type's own source.
      *
+     * <p>Added 2026-09-26 (the receiver question): when the type a MEMBERS question names is not declared
+     * in the sources but is a class of the running JDK, {@code declared} is that class's public methods,
+     * own and inherited, as {@code name(ParamSimpleType, ...)}, and {@code meant} is the first
+     * declaration of the called method in the sources as {@code Type.name(ParamType, ...)}, or null.
+     *
      * @param kind     the question
      * @param type     the simple name of the type the question is about
@@ -59,5 +64,6 @@
      *                 then {@code java.util.Name (JDK class)} for a class name the JDK declares
      * @param meant    for MEMBERS, the declared member the missing one names ({@link DeclaredMembers#meantBy}),
-     *                 or null when none; null for the other kinds
+     *                 or null when none -- for a JDK receiver, the sources' own declaration of the called
+     *                 method, or null; null for the other kinds
      */
     public record Answer(Kind kind, String type, String member, List<String> declared, String meant) {
@@ -77,5 +83,5 @@
     private static final Pattern OVERLOADS_PATTERN = Pattern.compile("method (\\w+) in (?:record|class) ([\\w.]+)");
     private static final Pattern SYMBOL_PATTERN = Pattern.compile(
-            "cannot find symbol (class|variable|method) (\\w+)(?:\\([^)]*\\))? in (?:class|interface|record|enum|variable \\w+ of type) ([\\w.$]+)");
+            "cannot find symbol (class|variable|method) (\\w+)(?:\\([^)]*\\))? in (?:(?:variable|record component) \\w+ of type|class|interface|record|enum) ([\\w.$]+)");
 
     /**
@@ -103,4 +109,6 @@
                     return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
                 }
+                Optional<Answer> receiver = jdkReceiver(type, memberName, sources);
+                if (receiver.isPresent()) return receiver;
             }
         }
@@ -136,4 +144,8 @@
             String kind = symbolMatcher.group(1), name = symbolMatcher.group(2), location = symbolMatcher.group(3);
             String locationType = location.substring(location.lastIndexOf('.') + 1);
+            if (kind.equals("method") && findSourceForType(locationType, sources).isEmpty()) {
+                Optional<Answer> receiver = jdkReceiver(locationType, name, sources);
+                if (receiver.isPresent()) return receiver;
+            }
             List<String> declared = new ArrayList<>(declarationsInSources(name, sources));
             if (kind.equals("class") || declared.isEmpty() && Character.isUpperCase(name.charAt(0))) {
@@ -145,4 +157,29 @@
 
         return Optional.empty();
+    }
+
+    /**
+     * The receiver question: {@code type} is not declared in the sources, so if it is a class of the
+     * running JDK, answer with what that class really declares -- its public methods, own and
+     * inherited -- and, as {@code meant}, where the sources declare the method that was called (a
+     * receiver typed Object usually means the element type was lost on the way). Empty when the JDK
+     * has no such class either.
+     */
+    private static Optional<Answer> jdkReceiver(String type, String member, Map<String, String> sources) {
+        List<Class<?>> classes = jdkTypes(type);
+        if (classes.isEmpty()) return Optional.empty();
+        java.util.TreeSet<String> declared = new java.util.TreeSet<>();
+        for (Class<?> c : classes) {
+            for (java.lang.reflect.Method m : c.getMethods()) {
+                if (m.isSynthetic() || m.isBridge()) continue;
+                declared.add(m.getName() + "(" + java.util.Arrays.stream(m.getParameterTypes())
+                        .map(Class::getSimpleName).collect(Collectors.joining(", ")) + ")");
+            }
+        }
+        String meant = declarationsInSources(member, sources).stream()
+                .filter(d -> d.endsWith("method)"))
+                .map(d -> d.substring(0, d.lastIndexOf(" (")))
+                .findFirst().orElse(null);
+        return Optional.of(new Answer(Kind.MEMBERS, type, member, new ArrayList<>(declared), meant));
     }
 
@@ -214,5 +251,10 @@
      */
     private static List<String> jdkClasses(String simpleName) {
-        java.util.TreeSet<String> found = new java.util.TreeSet<>();
+        return jdkTypes(simpleName).stream().map(c -> c.getName() + " (JDK class)").toList();
+    }
+
+    /** The public JDK classes {@link #jdkClasses} names, ordered by their full name. */
+    private static List<Class<?>> jdkTypes(String simpleName) {
+        java.util.TreeMap<String, Class<?>> found = new java.util.TreeMap<>();
         for (Module module : ModuleLayer.boot().modules()) {
             if (!module.getName().startsWith("java.")) continue;
@@ -221,5 +263,5 @@
                 try {
                     Class<?> c = Class.forName(pkg + "." + simpleName, false, ClassLoader.getPlatformClassLoader());
-                    if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) found.add(c.getName() + " (JDK class)");
+                    if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) found.put(c.getName(), c);
                 } catch (ClassNotFoundException | LinkageError ignored) {
                     // not in this package
@@ -227,5 +269,5 @@
             }
         }
-        return new ArrayList<>(found);
+        return new ArrayList<>(found.values());
     }
 
```
### origin -> port

```diff
--- origin/StuckQuestions.java
+++ port/StuckQuestions.java
@@ -68,4 +68,7 @@
 
     private static final Pattern MEMBERS_PATTERN = Pattern.compile("has no (\\w+)\\(");
+    /** javac's wording for a method called on a record component whose type the sources do not declare. */
+    private static final Pattern RECEIVER_METHOD_PATTERN = Pattern.compile(
+            "cannot find symbol method (\\w+)\\([^)]*\\) in record component \\w+ of type ([\\w.$]+(?:<[^>]*>)?)");
     /**
      * javac's own wording ("constructor X in record a.b.X"), or the grind detector's for "no suitable
@@ -88,4 +91,14 @@
         if (signature == null || sources == null || sources.isEmpty()) {
             return Optional.empty();
+        }
+
+        // A method called on a record component whose type is not the project's (T4-DY, the receiver question)
+        Matcher receiverMatcher = RECEIVER_METHOD_PATTERN.matcher(signature);
+        if (receiverMatcher.find()) {
+            String rawType = receiverMatcher.group(2);
+            String type = rawType.contains("<") ? rawType.substring(0, rawType.indexOf('<')) : rawType;
+            type = type.substring(type.lastIndexOf('.') + 1);
+            Optional<Answer> jdk = answerJdkReceiver(type, receiverMatcher.group(1), sources);
+            if (jdk.isPresent()) return jdk;
         }
 
@@ -103,4 +116,6 @@
                     return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
                 }
+                Optional<Answer> jdk = answerJdkReceiver(type, memberName, sources);
+                if (jdk.isPresent()) return jdk;
             }
         }
@@ -145,4 +160,36 @@
 
         return Optional.empty();
+    }
+
+    /**
+     * The receiver question (T4-DY, built by the loop in the receiver-questions toy case, ported with review
+     * fixes): a type the sources do not declare but the running JDK does, uniquely by that simple name. The
+     * answer is what that JDK class declares -- its public methods, own and inherited, as
+     * {@code name(ParameterSimpleType, ...)}, sorted -- and, as {@code meant}, where the sources declare the
+     * called method ({@code Type.name(ParameterTypes)}): a receiver typed Object usually lost its element type.
+     * Empty when the JDK has no such class, or more than one.
+     */
+    private static Optional<Answer> answerJdkReceiver(String type, String memberName, Map<String, String> sources) {
+        List<String> candidates = jdkClasses(type);
+        if (candidates.size() != 1) return Optional.empty();
+        Class<?> jdkClass;
+        try {
+            jdkClass = Class.forName(candidates.get(0).replace(" (JDK class)", ""), false, ClassLoader.getPlatformClassLoader());
+        } catch (ClassNotFoundException | LinkageError e) {
+            return Optional.empty();
+        }
+        java.util.TreeSet<String> declared = new java.util.TreeSet<>();
+        for (java.lang.reflect.Method m : jdkClass.getMethods()) {
+            declared.add(m.getName() + "(" + java.util.Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
+                    .collect(Collectors.joining(", ")) + ")");
+        }
+        String meant = null;
+        for (String d : declarationsInSources(memberName, sources)) {
+            if (d.endsWith("method)")) {
+                meant = d.substring(0, d.lastIndexOf(" ("));
+                break;
+            }
+        }
+        return Optional.of(new Answer(Kind.MEMBERS, type, memberName, new ArrayList<>(declared), meant));
     }
 
```
### origin -> proposed

```diff
--- origin/StuckQuestions.java
+++ proposed/StuckQuestions.java
@@ -69,4 +69,12 @@
     private static final Pattern MEMBERS_PATTERN = Pattern.compile("has no (\\w+)\\(");
     /**
+     * javac's wording for a method called on a receiver whose type it names: "in record component x of
+     * type T" or "in variable x of type T". T is kept as javac wrote it -- package and all -- because a
+     * written package leaves nothing to guess (port review 2026-09-27: dropping it made java.util.Map a bare
+     * "Map" to be looked up again).
+     */
+    private static final Pattern RECEIVER_METHOD_PATTERN = Pattern.compile(
+            "cannot find symbol method (\\w+)\\([^)]*\\) in (?:record component|variable) \\w+ of type ([\\w.$]+)");
+    /**
      * javac's own wording ("constructor X in record a.b.X"), or the grind detector's for "no suitable
      * constructor found" ("X has no constructor X(args)") -- the second was not recognised until slice B
@@ -86,9 +94,26 @@
      */
     public static Optional<Answer> answer(String signature, Map<String, String> sources) {
+        return answer(signature, sources, TypeAuthority.platform());
+    }
+
+    /**
+     * As {@link #answer(String, Map)}, with the authority that says which class a type name in the
+     * signature denotes when the sources do not declare it -- the running JDK by default; in the loop, the
+     * project's own classpath as well ({@code ProjectTypeAuthority}).
+     */
+    public static Optional<Answer> answer(String signature, Map<String, String> sources, TypeAuthority authority) {
         if (signature == null || sources == null || sources.isEmpty()) {
             return Optional.empty();
         }
 
-        // Try MEMBERS pattern first
+        // A method called on a receiver whose type javac names (T4-DY, the receiver question): answered only
+        // when that type is not the project's own -- the project's answers come from its sources below.
+        Matcher receiverMatcher = RECEIVER_METHOD_PATTERN.matcher(signature);
+        if (receiverMatcher.find()) {
+            Optional<Answer> receiver = receiverAnswer(receiverMatcher.group(2), receiverMatcher.group(1), sources, authority);
+            if (receiver.isPresent()) return receiver;
+        }
+
+        // Try MEMBERS pattern (after the receiver question)
         Matcher membersMatcher = MEMBERS_PATTERN.matcher(signature);
         if (membersMatcher.find()) {
@@ -103,4 +128,6 @@
                     return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
                 }
+                Optional<Answer> receiver = receiverAnswer(type, memberName, sources, authority);
+                if (receiver.isPresent()) return receiver;
             }
         }
@@ -145,4 +172,117 @@
 
         return Optional.empty();
+    }
+
+    /**
+     * Which class a type name in a failure denotes -- one authority, asked by every question that needs it,
+     * so the router cannot answer with a class the project does not mean. Empty is "unknown or ambiguous",
+     * never a guess.
+     */
+    @FunctionalInterface
+    public interface TypeAuthority {
+
+        /** The one class {@code name} (as the failure wrote it: simple or qualified) denotes, or empty. */
+        Optional<Class<?>> resolve(String name, Map<String, String> sources);
+
+        /** The running JDK's platform classes, by {@link #resolveAgainst}. */
+        static TypeAuthority platform() {
+            return (name, sources) -> resolveAgainst(name, sources, ClassLoader.getPlatformClassLoader(),
+                    JdkPlatformTypeIndex::exportedTypes);
+        }
+    }
+
+    /**
+     * The rule every {@link TypeAuthority} applies -- Java's own order for resolving a simple type name
+     * (JLS 6.4.1 / 7.5), so the router means the class the compiler would (port review 2026-09-27: the
+     * loop's and the first port's versions answered the project's own {@code Base64} with
+     * {@code java.util.Base64}'s methods; the independent review of this rule's first draft found that an
+     * explicit import must also outrank {@code java.lang} and a unique platform name):
+     * <ol>
+     *   <li>a package the failure WROTE is exact: that class, or nothing;</li>
+     *   <li>a type the project's sources declare is the project's -- never a platform or dependency class;</li>
+     *   <li>a single-type import in the sources ({@code import a.b.Name;}) names it: that class, or nothing
+     *       when it cannot be loaded -- and nothing when the sources import two different ones;</li>
+     *   <li>{@code java.lang} -- visible everywhere without an import;</li>
+     *   <li>on-demand imports ({@code import a.b.*;}) choose among the candidates {@code bySimpleName} lists;
+     *       without one, a single candidate is the one meant -- otherwise no answer.</li>
+     * </ol>
+     */
+    public static Optional<Class<?>> resolveAgainst(String name, Map<String, String> sources, ClassLoader loader,
+                                                   java.util.function.Function<String, java.util.Set<String>> bySimpleName) {
+        if (name == null || name.isBlank()) return Optional.empty();
+        String bare = name.contains("<") ? name.substring(0, name.indexOf('<')) : name;
+        if (bare.contains(".")) return load(bare, loader);
+        if (findSourceForType(bare, sources).isPresent()) return Optional.empty();
+        java.util.Set<String> singleImports = singleTypeImports(bare, sources);
+        if (singleImports.size() > 1) return Optional.empty();
+        if (singleImports.size() == 1) return load(singleImports.iterator().next(), loader);
+        Optional<Class<?>> lang = load("java.lang." + bare, loader);
+        if (lang.isPresent()) return lang;
+        List<String> candidates = new ArrayList<>(bySimpleName.apply(bare));
+        List<String> onDemand = candidates.stream().filter(fqn -> importedOnDemand(fqn, sources)).toList();
+        if (!onDemand.isEmpty()) candidates = onDemand;
+        return candidates.size() == 1 ? load(candidates.get(0), loader) : Optional.empty();
+    }
+
+    private static Optional<Class<?>> load(String fqn, ClassLoader loader) {
+        try {
+            Class<?> c = Class.forName(fqn, false, loader);
+            return java.lang.reflect.Modifier.isPublic(c.getModifiers()) ? Optional.of(c) : Optional.empty();
+        } catch (ClassNotFoundException | LinkageError e) {
+            return Optional.empty();
+        }
+    }
+
+    /** Every {@code import a.b.Name;} of that simple name in the sources. */
+    private static java.util.Set<String> singleTypeImports(String simpleName, Map<String, String> sources) {
+        Pattern single = Pattern.compile("^\\s*import\\s+([\\w.]+\\." + Pattern.quote(simpleName) + ")\\s*;", Pattern.MULTILINE);
+        java.util.Set<String> out = new java.util.TreeSet<>();
+        for (String content : sources.values()) {
+            if (content == null) continue;
+            Matcher m = single.matcher(content);
+            while (m.find()) out.add(m.group(1));
+        }
+        return out;
+    }
+
+    private static boolean importedOnDemand(String fqn, Map<String, String> sources) {
+        String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
+        Pattern onDemand = Pattern.compile("^\\s*import\\s+" + Pattern.quote(pkg) + "\\.\\*\\s*;", Pattern.MULTILINE);
+        for (String content : sources.values()) {
+            if (content != null && onDemand.matcher(content).find()) return true;
+        }
+        return false;
+    }
+
+    /**
+     * The receiver question: {@code type} is not the project's, and the authority names the one class it
+     * is. The answer is what that class declares -- its public methods, own and inherited, as
+     * {@code name(ParameterSimpleType, ...)}, sorted, compiler-generated bridge and synthetic methods left
+     * out -- and, as {@code meant}, where the sources declare the called method
+     * ({@code Type.name(ParameterTypes)}): a receiver typed Object usually lost its element type.
+     */
+    private static Optional<Answer> receiverAnswer(String type, String memberName, Map<String, String> sources,
+                                                   TypeAuthority authority) {
+        Optional<Class<?>> resolved = authority.resolve(type, sources);
+        if (resolved.isEmpty()) return Optional.empty();
+        java.util.TreeSet<String> declared = new java.util.TreeSet<>();
+        try {
+            for (java.lang.reflect.Method m : resolved.get().getMethods()) {
+                if (m.isBridge() || m.isSynthetic()) continue;
+                declared.add(m.getName() + "(" + java.util.Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
+                        .collect(Collectors.joining(", ")) + ")");
+            }
+        } catch (LinkageError | TypeNotPresentException e) {
+            // a dependency class whose own dependencies are missing: no answer rather than a broken prompt
+            return Optional.empty();
+        }
+        String meant = null;
+        for (String d : declarationsInSources(memberName, sources)) {
+            if (d.endsWith("method)")) {
+                meant = d.substring(0, d.lastIndexOf(" ("));
+                break;
+            }
+        }
+        return Optional.of(new Answer(Kind.MEMBERS, resolved.get().getSimpleName(), memberName, new ArrayList<>(declared), meant));
     }
 
@@ -210,20 +350,11 @@
 
     /**
-     * Public classes of that simple name in the running JDK's own modules, discovered rather than
-     * listed: every package a {@code java.*} module exports, asked whether it holds the name.
+     * Public classes of that simple name in the running JDK, from the platform index discovery also reads
+     * ({@link JdkPlatformTypeIndex}) -- one scan of the runtime image, not two.
      */
     private static List<String> jdkClasses(String simpleName) {
         java.util.TreeSet<String> found = new java.util.TreeSet<>();
-        for (Module module : ModuleLayer.boot().modules()) {
-            if (!module.getName().startsWith("java.")) continue;
-            for (String pkg : module.getPackages()) {
-                if (!module.isExported(pkg)) continue;
-                try {
-                    Class<?> c = Class.forName(pkg + "." + simpleName, false, ClassLoader.getPlatformClassLoader());
-                    if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) found.add(c.getName() + " (JDK class)");
-                } catch (ClassNotFoundException | LinkageError ignored) {
-                    // not in this package
-                }
-            }
+        for (String fqn : JdkPlatformTypeIndex.exportedTypes(simpleName)) {
+            if (load(fqn, ClassLoader.getPlatformClassLoader()).isPresent()) found.add(fqn + " (JDK class)");
         }
         return new ArrayList<>(found);
```
### loop -> port (what the port review changed in the loop's code)

```diff
--- loop/StuckQuestions.java
+++ port/StuckQuestions.java
@@ -4,4 +4,5 @@
 import com.github.javaparser.ParserConfiguration;
 import com.github.javaparser.ast.CompilationUnit;
+import com.github.javaparser.ast.Modifier;
 import com.github.javaparser.ast.body.ConstructorDeclaration;
 import com.github.javaparser.ast.body.MethodDeclaration;
@@ -9,10 +10,8 @@
 import com.github.javaparser.ast.body.TypeDeclaration;
 
-import java.lang.reflect.Method;
 import java.util.ArrayList;
 import java.util.List;
 import java.util.Map;
 import java.util.Optional;
-import java.util.TreeSet;
 import java.util.regex.Matcher;
 import java.util.regex.Pattern;
@@ -69,6 +68,12 @@
 
     private static final Pattern MEMBERS_PATTERN = Pattern.compile("has no (\\w+)\\(");
+    /** javac's wording for a method called on a record component whose type the sources do not declare. */
     private static final Pattern RECEIVER_METHOD_PATTERN = Pattern.compile(
             "cannot find symbol method (\\w+)\\([^)]*\\) in record component \\w+ of type ([\\w.$]+(?:<[^>]*>)?)");
+    /**
+     * javac's own wording ("constructor X in record a.b.X"), or the grind detector's for "no suitable
+     * constructor found" ("X has no constructor X(args)") -- the second was not recognised until slice B
+     * put the router in the loop, where the detector's wording is what it is asked.
+     */
     private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile(
             "constructor (\\w+) in (?:record|class) ([\\w.]+)|has no constructor (\\w+)\\(");
@@ -88,17 +93,19 @@
         }
 
-        // Try RECEIVER_METHOD pattern first (cannot find symbol method X in record component Y of type Z)
+        // A method called on a record component whose type is not the project's (T4-DY, the receiver question)
         Matcher receiverMatcher = RECEIVER_METHOD_PATTERN.matcher(signature);
         if (receiverMatcher.find()) {
-            String memberName = receiverMatcher.group(1);
             String rawType = receiverMatcher.group(2);
             String type = rawType.contains("<") ? rawType.substring(0, rawType.indexOf('<')) : rawType;
-            return answerJdkReceiver(type, memberName, sources);
-        }
-
-        // Try MEMBERS pattern
+            type = type.substring(type.lastIndexOf('.') + 1);
+            Optional<Answer> jdk = answerJdkReceiver(type, receiverMatcher.group(1), sources);
+            if (jdk.isPresent()) return jdk;
+        }
+
+        // Try MEMBERS pattern first
         Matcher membersMatcher = MEMBERS_PATTERN.matcher(signature);
         if (membersMatcher.find()) {
             String memberName = membersMatcher.group(1);
+            // Extract type name from signature: "Type has no member"
             String type = extractTypeFromMembersSignature(signature);
             if (type != null) {
@@ -109,5 +116,6 @@
                     return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
                 }
-                return answerJdkReceiver(type, memberName, sources);
+                Optional<Answer> jdk = answerJdkReceiver(type, memberName, sources);
+                if (jdk.isPresent()) return jdk;
             }
         }
@@ -155,14 +163,33 @@
 
     /**
-     * Answer a receiver question: the type is not in the sources, so check if it's a JDK class.
+     * The receiver question (T4-DY, built by the loop in the receiver-questions toy case, ported with review
+     * fixes): a type the sources do not declare but the running JDK does, uniquely by that simple name. The
+     * answer is what that JDK class declares -- its public methods, own and inherited, as
+     * {@code name(ParameterSimpleType, ...)}, sorted -- and, as {@code meant}, where the sources declare the
+     * called method ({@code Type.name(ParameterTypes)}): a receiver typed Object usually lost its element type.
+     * Empty when the JDK has no such class, or more than one.
      */
     private static Optional<Answer> answerJdkReceiver(String type, String memberName, Map<String, String> sources) {
-        Optional<Class<?>> jdkClass = findJdkClass(type);
-        if (jdkClass.isPresent()) {
-            List<String> declared = jdkPublicMethods(jdkClass.get());
-            String meant = findMethodDeclarationInSources(memberName, sources);
-            return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
-        }
-        return Optional.empty();
+        List<String> candidates = jdkClasses(type);
+        if (candidates.size() != 1) return Optional.empty();
+        Class<?> jdkClass;
+        try {
+            jdkClass = Class.forName(candidates.get(0).replace(" (JDK class)", ""), false, ClassLoader.getPlatformClassLoader());
+        } catch (ClassNotFoundException | LinkageError e) {
+            return Optional.empty();
+        }
+        java.util.TreeSet<String> declared = new java.util.TreeSet<>();
+        for (java.lang.reflect.Method m : jdkClass.getMethods()) {
+            declared.add(m.getName() + "(" + java.util.Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
+                    .collect(Collectors.joining(", ")) + ")");
+        }
+        String meant = null;
+        for (String d : declarationsInSources(memberName, sources)) {
+            if (d.endsWith("method)")) {
+                meant = d.substring(0, d.lastIndexOf(" ("));
+                break;
+            }
+        }
+        return Optional.of(new Answer(Kind.MEMBERS, type, memberName, new ArrayList<>(declared), meant));
     }
 
@@ -214,8 +241,8 @@
     }
 
-    private static String visibility(com.github.javaparser.ast.NodeList<com.github.javaparser.ast.Modifier> modifiers) {
-        for (com.github.javaparser.ast.Modifier m : modifiers) {
-            if (m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.PRIVATE) return "private ";
-            if (m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.PROTECTED) return "protected ";
+    private static String visibility(com.github.javaparser.ast.NodeList<Modifier> modifiers) {
+        for (Modifier m : modifiers) {
+            if (m.getKeyword() == Modifier.Keyword.PRIVATE) return "private ";
+            if (m.getKeyword() == Modifier.Keyword.PROTECTED) return "protected ";
         }
         return "";
@@ -250,91 +277,10 @@
     }
 
-    /**
-     * Find a JDK class by simple name, searching common JDK packages first, then falling back to
-     * a full module-system scan so that any exported {@code java.*} package is covered.
-     */
-    private static Optional<Class<?>> findJdkClass(String simpleName) {
-        String[] commonPackages = {
-                "java.lang", "java.util", "java.awt", "java.awt.image", "java.awt.peer",
-                "java.io", "java.net", "java.nio", "java.sql", "java.text", "java.time",
-                "javax.swing", "javax.swing.text", "javax.swing.event"
-        };
-        for (String pkg : commonPackages) {
-            try {
-                Class<?> c = Class.forName(pkg + "." + simpleName, false, ClassLoader.getPlatformClassLoader());
-                if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
-                    return Optional.of(c);
-                }
-            } catch (ClassNotFoundException | LinkageError ignored) {
-                // not in this package
-            }
-        }
-        // Fallback: scan all exported packages of java.* modules
-        for (Module module : ModuleLayer.boot().modules()) {
-            if (!module.getName().startsWith("java.")) continue;
-            for (String pkg : module.getPackages()) {
-                if (!module.isExported(pkg)) continue;
-                try {
-                    Class<?> c = Class.forName(pkg + "." + simpleName, false, ClassLoader.getPlatformClassLoader());
-                    if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
-                        return Optional.of(c);
-                    }
-                } catch (ClassNotFoundException | LinkageError ignored) {
-                    // not in this package
-                }
-            }
-        }
-        return Optional.empty();
-    }
-
-    /**
-     * List public methods of a JDK class as name(ParamSimpleType, ...), own and inherited.
-     */
-    private static List<String> jdkPublicMethods(Class<?> clazz) {
-        TreeSet<String> methods = new TreeSet<>();
-        for (Method m : clazz.getMethods()) {
-            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
-                String params = java.util.Arrays.stream(m.getParameterTypes())
-                        .map(Class::getSimpleName)
-                        .collect(Collectors.joining(", "));
-                methods.add(m.getName() + "(" + params + ")");
-            }
-        }
-        return new ArrayList<>(methods);
-    }
-
-    /**
-     * Find the first declaration of a method with the given name in the project sources,
-     * formatted as Type.name(ParamTypes).
-     */
-    private static String findMethodDeclarationInSources(String methodName, Map<String, String> sources) {
-        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
-        for (Map.Entry<String, String> entry : new java.util.TreeMap<>(sources).entrySet()) {
-            if (!entry.getKey().endsWith(".java") || entry.getValue() == null) continue;
-            CompilationUnit cu;
-            try {
-                cu = parser.parse(entry.getValue()).getResult().orElse(null);
-            } catch (RuntimeException e) {
-                continue;
-            }
-            if (cu == null) continue;
-            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
-                for (MethodDeclaration m : t.getMethods()) {
-                    if (m.getNameAsString().equals(methodName)) {
-                        String params = m.getParameters().stream()
-                                .map(p -> p.getType().asString())
-                                .collect(Collectors.joining(", "));
-                        return qualifiedName(t) + "." + methodName + "(" + params + ")";
-                    }
-                }
-            }
-        }
-        return null;
-    }
-
     private static String extractTypeFromMembersSignature(String signature) {
+        // Pattern: "Type has no member"
         int hasNoIndex = signature.indexOf("has no ");
         if (hasNoIndex < 0) return null;
         String before = signature.substring(0, hasNoIndex).trim();
+        // The type is the last word before "has no"
         String[] parts = before.split("\\s+");
         if (parts.length == 0) return null;
@@ -344,5 +290,8 @@
     /**
      * The source that DECLARES {@code simpleType}: the file named after it first, then any file with a
-     * real declaration of that name.
+     * real declaration of that name. Port review (2026-09-26): the loop's version matched
+     * {@code "class " + simpleType} as a substring, so {@code ParsedElement} also matched
+     * {@code class ParsedElementBuilder} and a name in a comment -- right on the archive only because
+     * the file map happened to list the named file first.
      */
     private static Optional<String> findSourceForType(String simpleType, Map<String, String> sources) {
@@ -398,4 +347,5 @@
         
         List<String> constructors = new ArrayList<>();
+        // For records, the canonical constructor comes first
         if (found instanceof RecordDeclaration r) {
             String params = r.getParameters().stream()
@@ -405,4 +355,5 @@
         }
         
+        // Add explicit constructors
         for (ConstructorDeclaration c : found.getConstructors()) {
             if (c.isPrivate()) continue;
@@ -443,5 +394,5 @@
         for (MethodDeclaration m : found.getMethods()) {
             if (!m.getNameAsString().equals(methodName)) continue;
-            if (!(isInterface || m.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PUBLIC))) continue;
+            if (!(isInterface || m.hasModifier(Modifier.Keyword.PUBLIC))) continue;
             String params = m.getParameters().stream()
                     .map(p -> p.getType().asString() + " " + p.getNameAsString())
@@ -452,3 +403,3 @@
         return overloads;
     }
-}+}
```
### port -> proposed (the change proposed after this review)

```diff
--- port/StuckQuestions.java
+++ proposed/StuckQuestions.java
@@ -68,7 +68,12 @@
 
     private static final Pattern MEMBERS_PATTERN = Pattern.compile("has no (\\w+)\\(");
-    /** javac's wording for a method called on a record component whose type the sources do not declare. */
+    /**
+     * javac's wording for a method called on a receiver whose type it names: "in record component x of
+     * type T" or "in variable x of type T". T is kept as javac wrote it -- package and all -- because a
+     * written package leaves nothing to guess (port review 2026-09-27: dropping it made java.util.Map a bare
+     * "Map" to be looked up again).
+     */
     private static final Pattern RECEIVER_METHOD_PATTERN = Pattern.compile(
-            "cannot find symbol method (\\w+)\\([^)]*\\) in record component \\w+ of type ([\\w.$]+(?:<[^>]*>)?)");
+            "cannot find symbol method (\\w+)\\([^)]*\\) in (?:record component|variable) \\w+ of type ([\\w.$]+)");
     /**
      * javac's own wording ("constructor X in record a.b.X"), or the grind detector's for "no suitable
@@ -89,19 +94,26 @@
      */
     public static Optional<Answer> answer(String signature, Map<String, String> sources) {
+        return answer(signature, sources, TypeAuthority.platform());
+    }
+
+    /**
+     * As {@link #answer(String, Map)}, with the authority that says which class a type name in the
+     * signature denotes when the sources do not declare it -- the running JDK by default; in the loop, the
+     * project's own classpath as well ({@code ProjectTypeAuthority}).
+     */
+    public static Optional<Answer> answer(String signature, Map<String, String> sources, TypeAuthority authority) {
         if (signature == null || sources == null || sources.isEmpty()) {
             return Optional.empty();
         }
 
-        // A method called on a record component whose type is not the project's (T4-DY, the receiver question)
+        // A method called on a receiver whose type javac names (T4-DY, the receiver question): answered only
+        // when that type is not the project's own -- the project's answers come from its sources below.
         Matcher receiverMatcher = RECEIVER_METHOD_PATTERN.matcher(signature);
         if (receiverMatcher.find()) {
-            String rawType = receiverMatcher.group(2);
-            String type = rawType.contains("<") ? rawType.substring(0, rawType.indexOf('<')) : rawType;
-            type = type.substring(type.lastIndexOf('.') + 1);
-            Optional<Answer> jdk = answerJdkReceiver(type, receiverMatcher.group(1), sources);
-            if (jdk.isPresent()) return jdk;
-        }
-
-        // Try MEMBERS pattern first
+            Optional<Answer> receiver = receiverAnswer(receiverMatcher.group(2), receiverMatcher.group(1), sources, authority);
+            if (receiver.isPresent()) return receiver;
+        }
+
+        // Try MEMBERS pattern (after the receiver question)
         Matcher membersMatcher = MEMBERS_PATTERN.matcher(signature);
         if (membersMatcher.find()) {
@@ -116,6 +128,6 @@
                     return Optional.of(new Answer(Kind.MEMBERS, type, memberName, declared, meant));
                 }
-                Optional<Answer> jdk = answerJdkReceiver(type, memberName, sources);
-                if (jdk.isPresent()) return jdk;
+                Optional<Answer> receiver = receiverAnswer(type, memberName, sources, authority);
+                if (receiver.isPresent()) return receiver;
             }
         }
@@ -163,24 +175,105 @@
 
     /**
-     * The receiver question (T4-DY, built by the loop in the receiver-questions toy case, ported with review
-     * fixes): a type the sources do not declare but the running JDK does, uniquely by that simple name. The
-     * answer is what that JDK class declares -- its public methods, own and inherited, as
-     * {@code name(ParameterSimpleType, ...)}, sorted -- and, as {@code meant}, where the sources declare the
-     * called method ({@code Type.name(ParameterTypes)}): a receiver typed Object usually lost its element type.
-     * Empty when the JDK has no such class, or more than one.
-     */
-    private static Optional<Answer> answerJdkReceiver(String type, String memberName, Map<String, String> sources) {
-        List<String> candidates = jdkClasses(type);
-        if (candidates.size() != 1) return Optional.empty();
-        Class<?> jdkClass;
+     * Which class a type name in a failure denotes -- one authority, asked by every question that needs it,
+     * so the router cannot answer with a class the project does not mean. Empty is "unknown or ambiguous",
+     * never a guess.
+     */
+    @FunctionalInterface
+    public interface TypeAuthority {
+
+        /** The one class {@code name} (as the failure wrote it: simple or qualified) denotes, or empty. */
+        Optional<Class<?>> resolve(String name, Map<String, String> sources);
+
+        /** The running JDK's platform classes, by {@link #resolveAgainst}. */
+        static TypeAuthority platform() {
+            return (name, sources) -> resolveAgainst(name, sources, ClassLoader.getPlatformClassLoader(),
+                    JdkPlatformTypeIndex::exportedTypes);
+        }
+    }
+
+    /**
+     * The rule every {@link TypeAuthority} applies -- Java's own order for resolving a simple type name
+     * (JLS 6.4.1 / 7.5), so the router means the class the compiler would (port review 2026-09-27: the
+     * loop's and the first port's versions answered the project's own {@code Base64} with
+     * {@code java.util.Base64}'s methods; the independent review of this rule's first draft found that an
+     * explicit import must also outrank {@code java.lang} and a unique platform name):
+     * <ol>
+     *   <li>a package the failure WROTE is exact: that class, or nothing;</li>
+     *   <li>a type the project's sources declare is the project's -- never a platform or dependency class;</li>
+     *   <li>a single-type import in the sources ({@code import a.b.Name;}) names it: that class, or nothing
+     *       when it cannot be loaded -- and nothing when the sources import two different ones;</li>
+     *   <li>{@code java.lang} -- visible everywhere without an import;</li>
+     *   <li>on-demand imports ({@code import a.b.*;}) choose among the candidates {@code bySimpleName} lists;
+     *       without one, a single candidate is the one meant -- otherwise no answer.</li>
+     * </ol>
+     */
+    public static Optional<Class<?>> resolveAgainst(String name, Map<String, String> sources, ClassLoader loader,
+                                                   java.util.function.Function<String, java.util.Set<String>> bySimpleName) {
+        if (name == null || name.isBlank()) return Optional.empty();
+        String bare = name.contains("<") ? name.substring(0, name.indexOf('<')) : name;
+        if (bare.contains(".")) return load(bare, loader);
+        if (findSourceForType(bare, sources).isPresent()) return Optional.empty();
+        java.util.Set<String> singleImports = singleTypeImports(bare, sources);
+        if (singleImports.size() > 1) return Optional.empty();
+        if (singleImports.size() == 1) return load(singleImports.iterator().next(), loader);
+        Optional<Class<?>> lang = load("java.lang." + bare, loader);
+        if (lang.isPresent()) return lang;
+        List<String> candidates = new ArrayList<>(bySimpleName.apply(bare));
+        List<String> onDemand = candidates.stream().filter(fqn -> importedOnDemand(fqn, sources)).toList();
+        if (!onDemand.isEmpty()) candidates = onDemand;
+        return candidates.size() == 1 ? load(candidates.get(0), loader) : Optional.empty();
+    }
+
+    private static Optional<Class<?>> load(String fqn, ClassLoader loader) {
         try {
-            jdkClass = Class.forName(candidates.get(0).replace(" (JDK class)", ""), false, ClassLoader.getPlatformClassLoader());
+            Class<?> c = Class.forName(fqn, false, loader);
+            return java.lang.reflect.Modifier.isPublic(c.getModifiers()) ? Optional.of(c) : Optional.empty();
         } catch (ClassNotFoundException | LinkageError e) {
             return Optional.empty();
         }
+    }
+
+    /** Every {@code import a.b.Name;} of that simple name in the sources. */
+    private static java.util.Set<String> singleTypeImports(String simpleName, Map<String, String> sources) {
+        Pattern single = Pattern.compile("^\\s*import\\s+([\\w.]+\\." + Pattern.quote(simpleName) + ")\\s*;", Pattern.MULTILINE);
+        java.util.Set<String> out = new java.util.TreeSet<>();
+        for (String content : sources.values()) {
+            if (content == null) continue;
+            Matcher m = single.matcher(content);
+            while (m.find()) out.add(m.group(1));
+        }
+        return out;
+    }
+
+    private static boolean importedOnDemand(String fqn, Map<String, String> sources) {
+        String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
+        Pattern onDemand = Pattern.compile("^\\s*import\\s+" + Pattern.quote(pkg) + "\\.\\*\\s*;", Pattern.MULTILINE);
+        for (String content : sources.values()) {
+            if (content != null && onDemand.matcher(content).find()) return true;
+        }
+        return false;
+    }
+
+    /**
+     * The receiver question: {@code type} is not the project's, and the authority names the one class it
+     * is. The answer is what that class declares -- its public methods, own and inherited, as
+     * {@code name(ParameterSimpleType, ...)}, sorted, compiler-generated bridge and synthetic methods left
+     * out -- and, as {@code meant}, where the sources declare the called method
+     * ({@code Type.name(ParameterTypes)}): a receiver typed Object usually lost its element type.
+     */
+    private static Optional<Answer> receiverAnswer(String type, String memberName, Map<String, String> sources,
+                                                   TypeAuthority authority) {
+        Optional<Class<?>> resolved = authority.resolve(type, sources);
+        if (resolved.isEmpty()) return Optional.empty();
         java.util.TreeSet<String> declared = new java.util.TreeSet<>();
-        for (java.lang.reflect.Method m : jdkClass.getMethods()) {
-            declared.add(m.getName() + "(" + java.util.Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
-                    .collect(Collectors.joining(", ")) + ")");
+        try {
+            for (java.lang.reflect.Method m : resolved.get().getMethods()) {
+                if (m.isBridge() || m.isSynthetic()) continue;
+                declared.add(m.getName() + "(" + java.util.Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
+                        .collect(Collectors.joining(", ")) + ")");
+            }
+        } catch (LinkageError | TypeNotPresentException e) {
+            // a dependency class whose own dependencies are missing: no answer rather than a broken prompt
+            return Optional.empty();
         }
         String meant = null;
@@ -191,5 +284,5 @@
             }
         }
-        return Optional.of(new Answer(Kind.MEMBERS, type, memberName, new ArrayList<>(declared), meant));
+        return Optional.of(new Answer(Kind.MEMBERS, resolved.get().getSimpleName(), memberName, new ArrayList<>(declared), meant));
     }
 
@@ -257,20 +350,11 @@
 
     /**
-     * Public classes of that simple name in the running JDK's own modules, discovered rather than
-     * listed: every package a {@code java.*} module exports, asked whether it holds the name.
+     * Public classes of that simple name in the running JDK, from the platform index discovery also reads
+     * ({@link JdkPlatformTypeIndex}) -- one scan of the runtime image, not two.
      */
     private static List<String> jdkClasses(String simpleName) {
         java.util.TreeSet<String> found = new java.util.TreeSet<>();
-        for (Module module : ModuleLayer.boot().modules()) {
-            if (!module.getName().startsWith("java.")) continue;
-            for (String pkg : module.getPackages()) {
-                if (!module.isExported(pkg)) continue;
-                try {
-                    Class<?> c = Class.forName(pkg + "." + simpleName, false, ClassLoader.getPlatformClassLoader());
-                    if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) found.add(c.getName() + " (JDK class)");
-                } catch (ClassNotFoundException | LinkageError ignored) {
-                    // not in this package
-                }
-            }
+        for (String fqn : JdkPlatformTypeIndex.exportedTypes(simpleName)) {
+            if (load(fqn, ClassLoader.getPlatformClassLoader()).isPresent()) found.add(fqn + " (JDK class)");
         }
         return new ArrayList<>(found);
```

The proposed version also needs `src/main/java/com/walpro/autobuilder/core/build/JdkPlatformTypeIndex.java` in the module (not shown here).

## Assessment: better / worse

In `ASSESSMENT.md` beside this file, written by an independent comparer (a fresh agent) that had no hand in the loop's code, the reference or the port. An opinion, kept apart from the measurements above.

## Decision (the operator)

- [ ] approve the port as it is
- [ ] approve with changes: ...
- [ ] decline (revert or do not port)
