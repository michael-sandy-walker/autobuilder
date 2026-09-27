# Assessment: proposed

Written by an independent reviewer that had no hand in any version. These are opinions. Line numbers refer to
`proposed/StuckQuestions.java`, and "patch" means `proposed/proposed.patch`. I read the code and did not run it.

## 1. The four requested changes, and the wrong-fact risk

1. **Sources before the JDK on the record-component path: done.** `resolveAgainst` returns empty when
   `findSourceForType(bare, sources)` is present (l.210), and that happens before any platform lookup.
   `receiverAnswer` is the single entry point for both the receiver path and the `has no` path. The TSV
   confirms it: the project's `Base64` gets no answer here, where port gave `java.util.Base64`. The
   project's own `Base64` still gets no answer from its sources on this wording, so the result is safe but
   not complete.
2. **Bridge and synthetic methods: done** (patch l.243). `String` no longer lists `compareTo(Object)`
   (TSV, plus `TypeAuthorityTest`).
3. **Class objects: done.** `TypeAuthority.resolve` returns `Class<?>`, and the `" (JDK class)"` round trip
   is gone from the receiver path. `jdkClasses` keeps the display string, but only for SYMBOL rendering.
4. **Stale comment: done** ("Try MEMBERS pattern (after the receiver question)").

**The wrong-fact risk is smaller but still present.** In two cases the proposed code can still answer a
non-JDK type with a JDK class:
- **A dependency type that shares a name with a unique JDK type.** This bites when the authority is JDK-only,
  as in `answer(sig, sources)`, and whenever `ProjectTypeAuthority` falls back to `platform()` (no pom,
  mvn failure or timeout, blank classpath). Example: the project imports
  `org.apache.commons.codec.binary.Base64` and the signature is `Base64 has no x()`. There is one
  candidate, so the imports are never consulted (l.216 filters only when `size() > 1`), and the answer is
  `java.util.Base64`.
- **java.lang wins over an explicit import** (l.211-212), even with a full classpath. Example: a project
  that imports `com.google.inject.Module` and fails with `Module has no configure()` gets `java.lang.Module`.
  In Java a single-type import shadows java.lang, so this rule gets the language wrong.

## 2. Compared with port and reference

**Better:**
- Port refused the common `List` case outright. Proposed settles it from imports (TSV `imports-util`) and
  still refuses when no import decides (`none`). Reference merged `java.util.List` and `java.awt.List`
  into one answer. Proposed does neither.
- A written package is honoured exactly: `java.util.Map` from the `variable ... of type` wording. Neither
  port nor loop did this.
- The `variable x of type T` wording is now covered, which the earlier review asked for.
- One index of the runtime image instead of two scans.

**Worse, or new risk:**
- **Unhandled LinkageError.** `receiverAnswer` calls `getMethods()` (l.242) outside any catch. For a
  dependency class whose method signatures name a class missing from the loader (optional deps of deps
  are not on the resolved classpath), this throws `NoClassDefFoundError` / `TypeNotPresentException`. The
  throw goes through `StuckDiagnosis.ask` into `StuckLayer.promptBlock`, the prompt build. `load()` only
  guards `forName`. This risk does not exist in the JDK-only port. I consider it the most likely field
  failure.
- **A second resolution rule.** The patch says there is "one answer to what is on this project's
  classpath". That holds for the jar scan, but the resolution rule is a second one:
  `PackageDiscoveryService` lets imports pick even when there is one candidate, and blocks a unique
  classpath hit shadowed by `java.base`. `resolveAgainst` does neither. Both answer "which class does this
  name mean", so this looks like one invariant with two homes.
- **Kind change.** `cannot find symbol method m in variable x of type <JDK type>` used to be SYMBOL and is
  now MEMBERS. The earlier review flagged the same scope change in the reference. `meantByImports` only
  fires on SYMBOL. No differential input covers this wording on a JDK type, so the change is unmeasured.
- **SYMBOL answers now read a wider index.** `jdkClasses` reads `JdkPlatformTypeIndex`, which covers every
  system module, `jdk.*` included, where the old scan read only exported `java.*` packages. None of the 41
  inputs changed, but that corpus does not probe names that live only in `jdk.*` modules. The same wider
  set feeds candidate counts, so a `jdk.*` twin can turn a unique name ambiguous (a refusal, which is safe).
- **Imports from any file decide for the whole project** (`importedBySources`). If two files import
  different candidates, the answer is a refusal, not a wrong fact. I accept this.
- **Cost and caching.** `forProject` runs on every `promptBlock`, even with nothing pending. The classpath
  is cached on pom mtime, so a pom edit can stall prompt building on mvn for up to 30 s. `scanJarsForType`
  opens every jar on every lookup, uncached. The loader it replaces is never `close()`d. With
  `initialize=false`, no static initialisers run. Thread safety looks fine.
- **Scope and size.** StuckQuestions grows from 405 to 467 lines, and the patch adds a 98-line Spring
  component and a 74-line test. The classpath authority is a new capability the earlier review did not
  ask for, and every new risk above lives in it.
- **Coverage gaps.** Neither the differential nor the test has a probe for the two remaining wrong-fact
  cases, the LinkageError, or the Kind change.

## 3. Verdict (opinion): approve with changes

1. **Let an import that names a different class win.** In `resolveAgainst`, before the java.lang and
   unique-candidate rules, refuse or defer to the import whenever any source single-type imports
   `x.y.<bare>` that is not the chosen class. This closes both remaining wrong-fact cases in a few lines.
2. **Catch `LinkageError` and `TypeNotPresentException` around `getMethods()`** and return empty.
3. **Add differential probes** for: a dependency import shadowing a unique JDK name, an import shadowing
   java.lang, and `variable x of type` on a JDK type (so the Kind change is visible).
4. **Close the loader it replaces, and resolve only when something is pending.** Optional, but cheap.

Where I am unsure: whether the MEMBERS-for-variable Kind change matters to any consumer beyond
`meantByImports`. Whether the uncached jar scan is noticeable on a large classpath (I did not measure it).
Whether the classpath authority should ship in this port or in its own change; that is the operator's
call.

## Re-check (revised proposal)

I read the revised `proposed/StuckQuestions.java` (489 lines), the patch, and the regenerated TSVs. I did not
run anything.

**Done:**
1. **Imports now outrank java.lang and a sole candidate.** `resolveAgainst` (l.210-225) now checks, in
   order: a written package, then the project's own type, then single-type imports (two different ones
   give no answer, one is loaded), then java.lang, then on-demand imports, then a sole candidate. In the
   `imports-foreign` tree, `Module has no configure()` and `Base64 has no decodeBase64(String)` now get no
   answer. Port and reference both answered them with `java.lang.Module` and `java.util.Base64`. The test
   `anExplicitImportOutranksJavaLangAndAUniquePlatformName` covers both.
2. **`getMethods()` is guarded.** It is wrapped in `catch (LinkageError | TypeNotPresentException)`
   (l.269-278) and returns empty.
3. **Probes added.** There are probes for the imports-foreign cases and for the `variable active of type
   java.util.Map<...>` wording. The Map probe went from no answer (port) to MEMBERS. A probe where
   `variable x of type <JDK type>` meets a method name the sources declare is still missing. That is the
   input where the SYMBOL-to-MEMBERS switch would be visible.
4. **The replaced loader is closed.** Resolving only when something is pending is not done
   (`forProject` still runs before `ledger.pending()`). I had marked that optional.

**New, minor:**
- **One wrong-fact case remains, narrower.** If the project imports a dependency package on demand
  (`import org.apache.commons.codec.binary.*;`), the authority is JDK-only, and the JDK has a single
  class of that name, the on-demand filter comes back empty and falls back to all candidates (l.223). The
  answer is then `java.util.Base64`. It needs both an on-demand import and the classpath fallback, so I
  think it is rare. The fix would be to refuse when some on-demand import names a package outside the
  candidates' packages. I cannot tell cheaply whether that package holds the name, so refusing there costs
  answers too.
- **Closing a loader another thread may still be using.** A `TypeAuthority` handed out before a classpath
  change keeps the closed loader. Loads through it then fail and give no answer. That is a refusal, not a
  wrong fact, so it is acceptable.
- **Imports inside comments.** The import regexes do not strip comments, so an import that is commented
  out still counts. At worst this refuses or picks the named class, and I think it is negligible.

**Updated verdict (opinion): approve.** Both wrong-fact cases I named are closed, and so is the crash path.
What remains is either a refusal or the rare on-demand-plus-fallback case above. Whether the
dependency-classpath authority belongs in this port, rather than in its own change, is still the
operator's call.
