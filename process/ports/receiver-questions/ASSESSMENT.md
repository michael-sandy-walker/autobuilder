# Assessment: better / worse

Written by an independent reviewer that had no hand in any version. The judgements below are opinions; the
line references are to each version's `StuckQuestions.java` or to the diffs in REVIEW.md.

## loop

- Better: it covers the two wordings the task names, and it strips generics from the record-component
  type.
- Worse, a wrong answer: the `RECEIVER_METHOD_PATTERN` branch runs before any sources lookup and
  `return`s its result whatever it is. If the record component's type is the project's own (a project
  `record Node` missing a method), it is looked up in the JDK first. `org.w3c.dom.Node` would win, as far
  as I can tell from reading the code (not run). If the type is in no JDK, the method returns empty and
  skips every later pattern.
- Worse: a qualified type (`of type java.util.Map<...>`) is not reduced to its simple name, so
  `findJdkClass` tries `java.lang.java.util.Map` and gives no answer.
- Worse, hard-coded data: `findJdkClass` has a hand-kept package priority list (`java.util` before
  `java.awt`, plus `java.awt.peer` and `javax.swing.*`). This list is what quietly settles `List` as
  `java.util.List`. The fallback scan covers every one of those packages anyway, so the table exists only to
  set the order.
- Worse, duplication: `findJdkClass` repeats the existing `jdkClasses` scan, and
  `findMethodDeclarationInSources` repeats `declarationsInSources`.
- Worse: bridge methods are kept (`compareTo(Object)` on String), and the `isPublic` filter over
  `getMethods()` does nothing.
- Worse, churn: 15 origin comment lines deleted, including the dated port-review provenance note on
  `findSourceForType`. The `Modifier` import was also expanded to fully qualified names for no reason.

## reference

- Better: it reuses the existing discovery by splitting `jdkClasses` into `jdkTypes`, so there is one scan
  and no literal table. Its `meant` reuses `declarationsInSources`. Sources are checked first on both paths
  (`findSourceForType(locationType, sources).isEmpty()`), so a project type always wins over a JDK class of
  the same name.
- Better: it skips synthetic and bridge methods (String has no `compareTo(Object)`), which is correct for
  "what the class declares". It extends `SYMBOL_PATTERN` rather than adding a second pattern, so the
  `variable x of type T` wording is covered as well as `record component`. The notes explain the
  alternation order.
- Worse: for an ambiguous simple name it merges every match. `List` gives the union of `java.util.List`
  and `java.awt.List` (`addActionListener`, `add(PopupMenu)`, ...). That list is not what any one class
  declares, and a small model reading it may call an AWT method on a collection. In my opinion this is the
  most dangerous behaviour of the three.
- Worse, scope: `cannot find symbol method m in class X` for a JDK X, a static call, now answers
  `Kind.MEMBERS` instead of the old `Kind.SYMBOL`. The spec does not ask for this, and any consumer that
  switches on `Kind` sees a change.

## port

- Better than the loop: it strips the package, falls through when the JDK has no answer, deletes the
  duplicate helpers and the literal table, and restores every origin comment. It adds zero removed lines.
- Better, ambiguity: `candidates.size() != 1` refuses rather than guesses. `List` gets no answer, and
  "no answer" seems safer to me than a wrong or merged one. The cost is that the most common real case,
  `java.util.List`, is lost.
- Worse, inherited from the loop and still present: the record-component branch is still first and still
  does not consult the sources, so the project `Node` versus `org.w3c.dom.Node` risk above remains.
  Only the `has no` path checks sources first.
- Worse: bridge methods are still listed (the String `compareTo(Object)` difference in the TSV). The
  answer also round-trips through the display string (`replace(" (JDK class)", "")` and a second
  `Class.forName`), where the reference's `jdkTypes` split is cleaner.
- Worse: only the `record component` wording is handled. `cannot find symbol method m in variable x of
  type Map` still gets the old SYMBOL answer, so the same question gets different answers depending on
  javac's wording. The restored comment "Try MEMBERS pattern first" is now false, because it is second.

## Verdict (opinion)

I would keep the port's shape, which means a small diff, no table and refusing on ambiguity, but ask for
four changes before approving:

1. Check `findSourceForType` before the JDK lookup in the record-component branch, or fold that wording
   into `SYMBOL_PATTERN` as the reference does.
2. Skip `isBridge()`/`isSynthetic()` methods.
3. Take `Class` objects from a `jdkTypes`-style split instead of re-parsing the display string.
4. Fix the stale "first" comment.

I am unsure about ambiguity: settling `List` from the failing file's imports would be correct, but the
signature does not reliably name the file. Refusing is the safe floor; merging, as the reference does,
should not be adopted. I am also unsure whether static methods belong in `declared`. All three versions
include them.

## Measured section

It is misleading on two counts. The "do NOT all agree" list shows 2 inputs, but 11 differ; the 9 where
only origin differs are left out. The 37 inputs also include no probe where the receiver type is also
declared in the sources, no qualified or `variable ... of type` receiver, and no static call. Those are
exactly the cases where the versions' risks diverge, so "13/13 and near-identical answers" understates
the differences.
