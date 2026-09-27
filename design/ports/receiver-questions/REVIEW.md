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

### Behaviour beyond the specification

Every input of `design/ports/stuck-router/differential.sh`, answered by each version. Listed: the inputs where the versions do NOT all agree (40 inputs in all).

Not listed: 10 input(s) where loop, reference and port agree with each other but differ from origin (the change itself); their answers are in `<version>/differential.tsv`.

- `the build fails: List has no getFirstItem()` (tree `none`)
  - origin: `(no answer)`
  - loop: `MEMBERS | List | getFirstItem | meant=null | [add(Object), add(int, Object), addAll(Collection), addAll(int, Collection), addFirst(Object), addLast(Object), clear(), contains(Object), containsAll(Collection), copyOf(Coll`
  - reference: `MEMBERS | List | getFirstItem | meant=null | [action(Event, Object), add(Object), add(PopupMenu), add(String), add(String, int), add(int, Object), addActionListener(ActionListener), addAll(Collection), addAll(int, Collec`
  - port: `(no answer)`
- `the build fails: String has no getLength()` (tree `none`)
  - origin: `(no answer)`
  - loop: `MEMBERS | String | getLength | meant=null | [charAt(int), chars(), codePointAt(int), codePointBefore(int), codePointCount(int, int), codePoints(), compareTo(Object), compareTo(String), compareToIgnoreCase(String), concat`
  - reference: `MEMBERS | String | getLength | meant=null | [charAt(int), chars(), codePointAt(int), codePointBefore(int), codePointCount(int, int), codePoints(), compareTo(String), compareToIgnoreCase(String), concat(String), contains(`
  - port: `MEMBERS | String | getLength | meant=null | [charAt(int), chars(), codePointAt(int), codePointBefore(int), codePointCount(int, int), codePoints(), compareTo(Object), compareTo(String), compareToIgnoreCase(String), concat`
- `the build fails: Clock.java: cannot find symbol method decode(String) in record component code of type Base64` (tree `shadow`)
  - origin: `(no answer)`
  - loop: `MEMBERS | Base64 | decode | meant=null | [equals(Object), getClass(), getDecoder(), getEncoder(), getMimeDecoder(), getMimeEncoder(), getMimeEncoder(int, byte[]), getUrlDecoder(), getUrlEncoder(), hashCode(), notify(), n`
  - reference: `(no answer)`
  - port: `MEMBERS | Base64 | decode | meant=null | [equals(Object), getClass(), getDecoder(), getEncoder(), getMimeDecoder(), getMimeEncoder(), getMimeEncoder(int, byte[]), getUrlDecoder(), getUrlEncoder(), hashCode(), notify(), n`
- `the build fails: Clock.java: cannot find symbol method stop() in record component t of type Timer` (tree `shadow`)
  - origin: `(no answer)`
  - loop: `MEMBERS | Timer | stop | meant=null | [cancel(), equals(Object), getClass(), hashCode(), notify(), notifyAll(), purge(), schedule(TimerTask, Date), schedule(TimerTask, Date, long), schedule(TimerTask, long), schedule(Tim`
  - reference: `(no answer)`
  - port: `(no answer)`

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

## Assessment: better / worse

In `ASSESSMENT.md` beside this file, written by an independent comparer (a fresh agent) that had no hand in the loop's code, the reference or the port. An opinion, kept apart from the measurements above.

## Decision (the operator)

- [ ] approve the port as it is
- [ ] approve with changes: ...
- [ ] decline (revert or do not port)
