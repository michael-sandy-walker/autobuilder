# Reference notes: StuckQuestions.answer

## What changed
Only `src/main/java/com/walpro/autobuilder/core/build/StuckQuestions.java`; `answer` is implemented.

- Three patterns read the signature, one per `Kind`: the grind detector's `X has no m(`
  (MEMBERS), and javac's `constructor X in <kind> a.b.X cannot be applied to given types`
  (CONSTRUCTOR) and `method m in <kind> a.b.X cannot be applied to given types` (OVERLOADS).
  Anything else is empty.
- The type is found by parsing the given sources (JavaParser, Java 17 level, nested types included),
  never by guessing a path from the name. The javac messages give a fully qualified name, matched
  against `TypeDeclaration.getFullyQualifiedName()`. The `has no` message gives only a simple name,
  which gets an answer only when exactly one source declares a type of that name.
- MEMBERS reuses `DeclaredMembers.of` and `DeclaredMembers.meantBy` unchanged. CONSTRUCTOR lists a record's
  canonical constructor (from its components), then the declared constructors, deduplicated so an
  explicit canonical one is not listed twice. OVERLOADS lists every method of that name. Each entry is
  `name(Type param, ...)`, with types printed by `Type.asString()`, so this matches `DeclaredMembers`
  (`Map<String,Object>`, no space).

## Ambiguities
- If a simple name is declared by several sources, the MEMBERS signature cannot say which one it
  means. I return empty ("the signature alone does not say what to look up") and do not pick the
  first one. The archive and spec have no such case.
- OVERLOADS and CONSTRUCTOR list declarations of every visibility, including private ones, because the
  class comment says "which overloads does its class declare". The spec does not check whether a
  private overload of the named method should appear.
- The javac variants `no suitable method/constructor found for ...` pose the same questions but
  do not appear in the archive, so they are not matched.
- A javac-reported type is looked up in sources of any path, not only under `src/main/java`.
