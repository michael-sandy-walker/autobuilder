# Assessment: stuck-questions (first version)

An independent opinion by a comparer who wrote none of the versions. It covers the three versions as they stand in this packet. The measured part is in REVIEW.md, where all three answer all 44 inputs the same way. Every difference below therefore lies outside the archive and has not been run. Each one comes from reading the code.

## Per version

**loop** (225 lines)
- Better: the javac patterns do not need anything after the type name. With a generic declaring type, where javac writes `constructor Box in class a.b.Box<T> cannot be applied`, `([\w.]+)` still captures `a.b.Box`. The reference does not match that input at all.
- Worse, correctness:
  - `findSourceForType` does a substring `contains("class " + name)`. So `ParsedElement` matches `class ParsedElementBuilder`, and it also matches a mention in a comment or a string. The port fixed this.
  - javac gives a fully qualified name, and the code throws the package away and looks the type up by simple name. If two sources declare `Foo` in different packages, the code answers from whichever comes first in the map.
  - The patterns only accept `in (?:record|class)`. javac's `in interface` and `in enum` are not recognised.
  - Hard-coded visibility filters: private constructors are dropped, and in a class only PUBLIC methods count as overloads. That drops package-private and protected overloads, which same-package callers really do hit. It also means an enum's constructors, which are always private, can never be listed.
  - An explicit canonical record constructor is listed twice, once from the components and once from `getConstructors()`.
  - A varargs parameter prints as `String name`, not `String... name`.
- Worse, clarity and size: `extractConstructors` and `extractOverloads` repeat the same parse-and-find block. The three matcher blocks in `answer` are near copies of each other. It also has an unused `typeName` local and unused imports. For MEMBERS, a qualified `a.b.Foo has no x(` gives empty, because the last token keeps its package.

**reference** (152 lines)
- Better:
  - A javac-reported type is matched by `getFullyQualifiedName()`, which is exact for same-name types in different packages and for nested types (`a.b.Outer.Inner`).
  - For MEMBERS, it gives no answer when more than one source declares the simple name. That fits the class contract: "empty is: the signature alone does not say".
  - It lists members of every visibility, accepts any declaring kind (`in \w+`), removes the duplicate canonical constructor, and prints varargs.
  - It has one parse helper and one signature formatter, and is the smallest of the three.
- Worse:
  - The patterns require ` cannot be applied to given types` straight after `[\w.]+`. So a generic declaring type (`a.b.Box<T>`) gets no answer, and neither does a signature the grind detector cut short.
  - It parses every source for every question. That costs nothing on toy trees, but the loop and port versions parse only one file.
  - The ambiguity rule refuses in a real case: a nested `Builder` inside several classes gives no answer for `Builder has no x(`. That is safe but loses the answer.
  - Listing private overloads may send the model toward a method it cannot call. This is a design choice, not a bug. The notes say so.

**port** (237 lines, the loop's code plus one fix)
- The fix to `findSourceForType` is right as far as it goes. It uses a word-bounded declaration regex, tries the file named after the type first, and strips comments.
- What is still wrong:
  - A mention inside a string literal still counts as a declaration.
  - A `/*` inside a string, such as a `"**/*.java"` glob, can swallow code up to the next `*/`.
  - When `src/main` and `src/test` both have a `Foo.java`, map order decides which one is used.
- It keeps every other weakness the loop has: the package is thrown away, the patterns take only `(?:record|class)`, the visibility filters stay, as do the duplicate canonical constructor and the missing varargs marker, and the parse code is still duplicated. It also keeps the loop's generic-type advantage.

## Verdict

**Approve with changes.** The port does not break any archived case, and the one fix it has is a real improvement. The changes, with what the later growth of the class did about each (I read the current `src/.../StuckQuestions.java`, 489 lines):
1. Look up the javac-reported type by its fully qualified name, not by the simple name. **Not addressed.** The CONSTRUCTOR and OVERLOADS paths still keep only the part after the last dot.
2. Accept any declaring kind: `in (?:class|record|interface|enum)`. **Not addressed.** The later SYMBOL pattern accepts all four, but OVERLOADS and CONSTRUCTOR still do not.
3. Drop the PUBLIC-only and not-private filters, or decide on purpose and document it. **Not addressed** (lines 443 and 480).
4. Remove the duplicate canonical record constructor and print varargs. **Not addressed.**
5. Make MEMBERS return empty when several sources declare the simple name. **Partly moot.** The later receiver path and `TypeAuthority` deal with JDK and dependency names. Ambiguity among the project's own sources still goes to the first match in the map.
6. The "no suitable constructor" wording. **Partly addressed.** The later growth handles the grind detector's form `has no constructor X(`. javac's own "no suitable method found" is still not recognised, and neither the reference nor the loop version recognises it either.
7. Factor out the duplicated parse block. This matters less, but the class has since doubled in size, so it is cheaper to do now than later.

If you adopt the reference's approach for item 1, loosen its patterns to allow an optional `<...>`, or it loses generic types.

**Where I am unsure:**
- Whether the grind detector ever records generic or cut-short javac text. If it never does, the loop's generic advantage is only theoretical.
- Whether listing non-public overloads helps or misleads the model. That is a design choice for the operator, not something the code settles.
- Whether JavaParser 3.25.7 at JAVA_17 fails to parse the Java 21 sources a target might contain. All three versions share that risk.

## Probes that would separate the versions

1. **Same simple name, two packages.** Sources: `a/x/Foo.java` (`record Foo(int n)`) and `b/y/Foo.java` (`record Foo(String s)`), in that map order. Signature: `the build fails: T.java: constructor Foo in record b.y.Foo cannot be applied to given types;`. The reference expected to answer `Foo(String s)`. Loop and port expected to answer `Foo(int n)`.
2. **Generic declaring type.** `class Box<T> { public void put(T t){} public void put(T t, int i){} }`. Signature: `method put in class a.Box<T> cannot be applied to given types;`. Loop and port expected to answer with both overloads. The reference expected to answer empty.
3. **Interface or enum.** `method m in interface a.Shape cannot be applied to given types;` against `interface Shape { void m(int x); }`. The reference expected to answer. Loop and port expected to answer empty.
4. **Package-private overload and varargs.** `class Foo { void bar(int a){} public void bar(String... s){} }` with `method bar in class a.Foo cannot be applied to given types;`. Reference expected: `bar(int a), bar(String... s)`. Loop and port expected: `bar(String s)`, which is both missing an overload and wrong.
5. **Explicit canonical constructor.** `record P(int x) { public P(int x) { this.x = x; } }` with `constructor P in record a.P cannot be applied to given types;`. Loop and port expected to list `P(int x)` twice. The reference expected to list it once.
6. **Simple name declared twice for MEMBERS.** `src/main/.../Foo.java` and `src/test/.../Foo.java` (a test double), with `Foo has no bar(`. The reference expected to answer empty. The port expected to answer from whichever file comes first in the map.
