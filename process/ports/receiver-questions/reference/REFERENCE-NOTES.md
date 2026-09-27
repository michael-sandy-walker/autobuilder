# Reference notes

Changed only `src/main/java/com/walpro/autobuilder/core/build/StuckQuestions.java`.

- New private `jdkReceiver(type, member, sources)`. If the type is a public class of the running JDK, it
  answers `Kind.MEMBERS`. `declared` holds that class's public methods, own and inherited, found with
  `Class.getMethods()` and written as `name(ParamSimpleType, ...)`. Synthetic and bridge methods are skipped,
  and the list is deduplicated and sorted. `meant` is the first `(... method)` entry that the existing
  `declarationsInSources` finds for the called name, without its suffix (`Type.name(ParamTypes)`), or null.
- It runs only where the sources gave no answer: after the MEMBERS path finds no source for the type, and
  in the SYMBOL path for `cannot find symbol method ...` when the location type is not declared in the sources.
  Answers taken from the sources come first, as before (a project class named `Object` still wins).
- The JDK class lookup is the existing module and package scan behind `jdkClasses`, split into
  `jdkTypes` so it returns the `Class` objects. It is still discovered at runtime, not a list of names.
- `SYMBOL_PATTERN` also accepts `in record component X of type T`. Both "of type" forms come before the
  bare `record` choice, because otherwise `record` matches first and the word `component` is read as the type.

## Ambiguities
- If more than one public JDK class has the simple name (e.g. `List`: java.util and java.awt), the answer
  joins all their methods. The signature does not say which one was meant; the imports of the failing file
  could decide it, but the signature does not name a file reliably.
- The "first declaration" for `meant` follows the path's order in the sources (TreeMap) and includes private
  methods. The spec says "first declaration", so private ones were not filtered out.
- Static methods of the JDK class are included (`getMethods()` returns them); the spec says "public methods,
  own and inherited" and does not say whether static ones count.
- `cannot find symbol method m in class X`, where X is a JDK class (a static call), is also treated as a
  receiver question. The spec only shows the `of type` wording.
