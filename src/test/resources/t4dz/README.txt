T4-DZ offline slice: the archived stuck questions the structural router leaves open, frozen as they were.

Corpus (2026-09-26, StuckQuestionsArchiveReplayTest over the 11 archived runs with grind events): 38 grind
signatures re-derived through the real GrindDetector.signaturesOf; StuckQuestions answers 31. Of the 7 left,
the protected-write refusal is now answered by structure (T4-DY slice B's PROTECTED_WRITE question), which
leaves these 6 -- 3 UNANSWERED, 3 AMBIGUOUS (java.util.List vs java.awt.List):

  q1-indexOf-on-map          roles 2026-09-25_19-57-35   indexOf called on a record component that is a Map
  q2-list-to-int             roles 2026-09-25_20-26-27   no E so that List<E> conforms to int
  q3-inference-bounds        totals 2026-09-25_20-51-35  inference variable E has incompatible bounds
  q4-list-ambiguous-class    totals                      cannot find symbol class List
  q5-list-ambiguous-variable totals                      cannot find symbol variable List (List.of)
  q6-list-ambiguous-record   totals                      cannot find symbol class List (ChannelRequest)

Each question dir: tree/ = the live tree the loop had (the adopted module; a failed candidate is rolled back),
candidate/ = the failing candidate from the run's mutation record, COMPILER_OUTPUT.txt = the compiler's own
block from the run log, question.json = the signature and the ground truth (truth_regex, truth_in_words),
written from the archive BEFORE any command was run.

"Answered correctly" = the output of a command run in a read-only copy of tree/ + candidate/ +
COMPILER_OUTPUT.txt contains a match for truth_regex (each command's output capped at 80 lines).

THE BAR, stated 2026-09-26 before measuring:
  - Ceiling (hand-written allow-listed commands, one per question): >= 5 of 6. Below that the command
    set cannot answer these questions at all, and the design is wrong, not the proposer.
  - Model arm (the local model, qwen3.8, proposes at most 3 allow-listed commands per question from the
    question, the compiler block and a file listing; 3 repetitions): a question counts when >= 2 of its
    3 repetitions answer it. >= 4 of 6 -> worth wiring as an authority after structure. 2-3 of 6 -> wire
    only with an aid to the proposer. <= 1 of 6 -> the local model cannot drive it; do not wire.
  - Safety, every arm: 0 writes to the copy (content hash before/after), every command outside the
    allow-list refused and counted, every command inside a 10 s budget.

STRUCTURAL LOOKUPS (T4-DY, the user's decision 2026-09-26: "first add the two structural lookups"), bar stated
before they were built:
  - (a) an ambiguous name is resolved by what the failing file IMPORTS as adopted (else what the rest of the
    project imports); (b) for a failure at a line naming no type, the types that line constructs, as declared
    in the adopted tree vs as declared by the change.
  - Measured through the REAL detector and layer (compiler block + candidate files + adopted tree), on the
    FIRST answer the ladder delivers for the signature -- not on whether a later whole-file dump happens to
    contain the fact (slice B's last question shows the declaring file whole; that would "answer" all six
    and prove nothing about the lookups).
  - Pass: q4-q6 -- the first answer names java.util.List as the one meant; q2-q3 -- the first answer states
    TickResult as adopted (ParticipationWindows windows, List<ParticipationWindow> expired) beside the change's
    differing declaration. 5 of 5 expected; q1 (indexOf on a Map) is covered by neither lookup and is expected
    to stay unanswered at the first answer -- it stays the terminal agent's case.
