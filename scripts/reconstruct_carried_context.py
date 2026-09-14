#!/usr/bin/env python3
"""Rebuild the carried cycle context of a checkpoint taken BEFORE contexts were captured
(2026-09-14), from the run's own logs, so a rewind to that point can settle from it.

Written for one cut point -- run 2026-09-13_09-44-03 at cycle-1789286226879, the second
StoreCoherenceHeuristic firing, streak 2 -- and kept as the record of how that context was
made (the archive's __context.provenance names this file). The inputs it reads are slices of
that run's autobuilder log saved beside it; the constants at the top say which. To do this for
another point: pick a template context from the same lineage (a real meta.json), then override
every field the next tick READS (ContextLineageTracker's carry-forwards, the signal ledgers, the
interpretation, the analysis outcome) from the log, and leave the coordinator-recomputed fields to
the template. Verify the result by diffing the first rendered prompt after the rewind against the
original run's next prompt -- what the model was shown is the evidence, not how the file was made.

Every field is tagged LOG (from the run's logs), TEMPLATE (recomputed at the next tick or unread
on the path under test), cleared, derived, or RE-KEYED (the streak map: the old build keyed by
heuristic name, the new build by name|subject). The manifest is written beside the output.
"""
import json, re, sys, os, copy

ROOT = '/home/anugeshtu/IdeaProjects/autobuilder'
S = '/tmp/claude-1000/-home-anugeshtu-IdeaProjects-autobuilder/3d980bc3-4d26-4376-9b7f-cdf3e3cff107/scratchpad'
CYCLE = 'cycle-1789286226879'
PREV = 'cycle-1789286082147'
SUB = 'goal-6a16c221-b410-4750-b929-19f28f0e91f1'
ROOTGOAL = 'goal-850ace8a-1d27-4bce-8940-1904345d6358'
KEY_TEMPLATE = None  # filled from the template's own key format
prov = {}

T = json.load(open(f'{ROOT}/data/firestarter/mutations/cycle-1789409469209/meta.json'))
C = copy.deepcopy(T)
for k in list(C.keys()):
    prov[k] = 'TEMPLATE'

def setf(k, v, why):
    C[k] = v; prov[k] = why

# ---- identity of the cut point (LOG) ----
setf('cycleName', CYCLE, 'LOG')
setf('parentCycleName', PREV, 'LOG')
setf('mutationPath', f'mutations/{CYCLE}', 'LOG')
setf('snapshotPath', f'snapshots/{CYCLE}', 'LOG')
setf('lastAttemptId', CYCLE, 'LOG (EvolutionAttempt node id = cycle id)')
setf('parentAttemptId', PREV, 'LOG (previous attempt; format differs from a merged-attempt id, unused on the path under test)')
setf('recombinationSourceCycleName', PREV, 'LOG (DIVERGENCE_REQUEST open at the cut, as in the template)')
setf('currentGoalId', ROOTGOAL, 'LOG'); setf('currentSubGoalId', SUB, 'LOG'); setf('currentSubGoalParentId', ROOTGOAL, 'LOG')
setf('analysisScopeGoalId', None, 'cleared'); setf('analysisScopeSubGoalId', None, 'cleared')

# ---- the T2-ES cut: streak two, warning rendered, verdict relayed (LOG, RE-KEYED) ----
RATIONALE = "parallel store introduced — entity 'Book' declared in BookRepository:12, BookStore:14; writes to one are invisible to the other"
setf('criticalHeuristicStreaks', {'StoreCoherenceHeuristic|Book': 2},
     'RE-KEYED: old build recorded {"StoreCoherenceHeuristic": 2}; subject Book is in the logged rationale')
setf('persistentHeuristicWarnings', [f'[StoreCoherenceHeuristic] (critical for 2 consecutive cycles): {RATIONALE}'], 'LOG line 9956 verbatim')
setf('rejectionFeedback', [f'StoreCoherenceHeuristic: {RATIONALE}'], 'LOG (T4-BX relay format as in template)')
FEEDBACK = f"CRITICAL: Heuristic 'StoreCoherenceHeuristic' overruled (0.90): {RATIONALE}"
evalOutcome = {'score': 0.0, 'mutationState': 'REJECTED', 'goalProgress': 'STAGNATING', 'feedbackMessage': FEEDBACK, 'recommendation': None}
setf('lastEvaluationOutcome', evalOutcome, 'LOG (behaviour mutation_negotiated / mutation_rejected)')
setf('evaluationOutcome', evalOutcome, 'LOG')
setf('evaluationFeedback', FEEDBACK, 'LOG')
setf('mutationState', 'REJECTED', 'LOG'); setf('goalProgress', 'STAGNATING', 'LOG'); setf('cycleResultState', 'STAGNATING', 'LOG cycle_end')
setf('merged', False, 'LOG'); setf('subgoalPromoted', False, 'LOG'); setf('evolutionRanThisCycle', True, 'LOG')
setf('score', 0.0, 'LOG'); setf('lastScore', 0.7896666666666667, 'LOG line 9957 Evaluation Score: Last=0.7896666666666667')
sr = dict(C['scoringResult']); sr['globalScore'] = 0.7896666666666667; sr['lastGlobalScore'] = 0.7896666666666667; sr['goalId'] = SUB
setf('scoringResult', sr, 'LOG line 9895 (per-file maps TEMPLATE)')
setf('lastGoalProgressApplication', {'goalId': SUB, 'promoted': False, 'previousSubGoalId': SUB, 'newSubGoalId': SUB, 'rootAutoCompleted': False}, 'LOG')
setf('deliberationResult', {'mode': 'UNDIRECTED', 'confidence': 0.55,
     'rationale': 'Analysis produced executable subgoals for the current scope → proceed with execution flow.',
     'considerations': [], 'observations': [], 'meta': {}, 'proposedRequests': [], 'proposedWindows': []}, 'LOG deliberation_outcome (confidence TEMPLATE)')
setf('cumulativeStagnatingCycles', 5, 'LOG interpretation: five consecutive cycles (recomputed from goal metadata at next tick anyway)')
setf('stagnationCircuitBreakerActive', False, 'LOG (no breaker line)')
setf('evaluatorRepeatRetries', 0, 'LOG (no repeat-refusal line)')
setf('consecutiveBuildFixAttempts', 0, 'TEMPLATE/LOG (no BUILD_FIX injection in cycle)')
setf('plateauCycles', 0, 'TEMPLATE'); setf('injectorFixes', [], 'LOG'); setf('skippedProposalMatches', None, 'cleared')
setf('steeringSuppressed', False, 'TEMPLATE'); setf('restageSuppressed', False, 'TEMPLATE')
setf('pausedReason', None, 'cleared'); setf('goalExhaustedReason', None, 'cleared'); setf('scopeGuardResult', None, 'cleared')
setf('rolledBackThisCycle', False, 'LOG (T1-DW probe rollback is not a merge rollback)'); setf('lastRollback', None, 'LOG'); setf('lastRollbackPoint', None, 'cleared')
setf('packageFragmentationThisCycle', False, 'LOG'); setf('packageFragmentation', None, 'LOG')
setf('sameFailureStreakKey', None, 'derived at next tick'); setf('sameFailureStreak', 0, 'derived at next tick')
setf('unmergedEvolutionWork', True, 'LOG'); setf('evolutionFingerprintChangedThisCycle', True, 'LOG (EVOLUTION_FINGERPRINT_CHANGED in F3 signals)')

# ---- fingerprints (LOG: mutationId == lastEvolutionFingerprint, verified on the template run) ----
FP2 = '338a1f75c7850f5f54659b77047b5eef0f14990195b2bd934baededae2107217'
FP1 = '89395ad491aff260c8c13b17d792e616424a56ea9ab564cfbb9dd31bebef9285'
recent = [FP2, FP1, '34487d2523ecb4b2a70829ada55f9548ecd392f29bb3bd4081bff707ae9ba2fc',
          'fead80d24174cea25dd596d174acb53008e97846b4c4971d642c9d0010818325',
          '22ee086c2e80715dbbb810bfa18a64765b72a6ad74b4755209ed3af3fb6f2b6c']
setf('lastEvolutionFingerprint', FP2, 'LOG behaviour mutation_proposed.mutationId')
setf('lastEvaluatedEvolutionFingerprint', FP2, 'LOG (evaluated this cycle)')
setf('lastRecombinationFingerprint', None, 'TEMPLATE')
setf('recentEvolutionFingerprints', recent[:len(T['recentEvolutionFingerprints'])], 'LOG behaviour (newest first, same length as template)')
setf('previousAcceptedCycleName', None, 'TEMPLATE')

# ---- the evolution outcome of the cut cycle (LOG: raw response, 15 fences) ----
raw = open(f'{S}/F2-evolution-response.txt').read()
body = raw.split('[LLM RESPONSE]\n-----\n', 1)[1]
end = body.rfind('```')
body = body[:end + 3]
files = []
for m in re.finditer(r'```(\w+) \[([^\]]+)\]\n(.*?)\n```', body, re.S):
    files.append({'path': m.group(2), 'content': m.group(3) + '\n'})
assert len(files) == 15, len(files)
evo = {'addedOrChangedFiles': files, 'deletedFiles': [], 'summary': 'Recombination produced 15 file changes and 0 deletions.',
       'rawResponse': body, 'recombined': True, 'noOp': False}
setf('evolutionOutcome', evo, 'LOG lines 9306-9872 (15 fenced files)')
setf('lastEvolutionOutcome', evo, 'LOG')
setf('lastAcceptedMutationFileCount', 4, 'TEMPLATE')

# ---- build state (LOG) ----
bo = dict(C['buildOutcome']); bo['cycleId'] = 'cycle-1789285626434'
setf('buildOutcome', bo, 'LOG cycle_end buildOutcome=COMPILE_OK (stderrTail TEMPLATE; cycleId = last merge cycle)')
tail = open(f'{S}/F2-probe-stderrTail.txt').read().rstrip('\n')
setf('lastProbeOutcome', {'result': 'COMPILE_ERROR', 'stderrTail': tail, 'durationMs': 6664, 'command': 'mvn compile', 'cycleId': f'{CYCLE}-probe'},
     'LOG (BuildExecutor COMPILE_ERROR 6664ms; tail = the 20 lines the next prompt rendered)')
OUT = f'{ROOT}/data/firestarter/output/src/main/java/com/example/library/domain/web/'
MSG = 'incompatible types: inference variable T has incompatible bounds'
diags = [{'file': OUT + 'MemberController.java', 'line': 35, 'column': 17, 'message': MSG},
         {'file': OUT + 'BookController.java', 'line': 40, 'column': 17, 'message': MSG},
         {'file': OUT + 'MemberController.java', 'line': 35, 'column': 17, 'message': MSG},
         {'file': OUT + 'BookController.java', 'line': 40, 'column': 17, 'message': MSG}]
setf('lastAuthorErrors', {'diagnostics': diags}, 'LOG probe stderr (4 javac diagnostics, authorErrorCount 4)')
diag = open(f'{S}/F2-buildDiagnostic.txt').read().rstrip('\n')
setf('buildDiagnostic', diag, 'LOG (the BUILD DIAGNOSTIC block the next prompt rendered)')

# ---- interpretation (LOG lines 10279-10281) ----
setf('interpretationSummary', "The system has experienced five consecutive cycles (cycle-1789285691360 through cycle-1789286226879) where mutations were REJECTED with a score of 0.0. The failure mode has shifted from persistent compile probe errors to a critical architectural violation (StoreCoherenceHeuristic) where the `Book` entity is declared in parallel stores (`BookRepository` and `BookStore`), causing data visibility issues. The planner is repeatedly attempting to fix type inference errors in controllers without resolving the underlying store coherence conflict, indicating a fundamental flaw in the current evolution direction.", 'LOG')
setf('interpretationDirectives', "Inject DIVERGENCE_REQUEST to halt the current evolution path. Prioritize resolving the StoreCoherenceHeuristic violation by unifying the `Book` entity storage (removing the parallel declaration in `BookStore` or `BookRepository`) before attempting any further controller fixes or feature work.", 'LOG')
setf('evaluationInterpretation', None, 'cleared')

# ---- planner outcome of the cut cycle (LOG lines 8992-9003: #SubGoalAppend refused, backlog full) ----
po = dict(C['lastPlannerOutcome']) if C.get('lastPlannerOutcome') else {}
po.update({'updated': False, 'newGoals': ['[BUILD_FIX] Fix type inference errors in BookController.java and MemberController.java where a Map is being passed to a method expecting a domain object (Book/Member)'],
           'message': 'Build is broken due to type inference errors in controllers. Appending a build-fix subgoal to resolve compilation before any feature work.'})
setf('lastPlannerOutcome', po, 'LOG (shape TEMPLATE; injection skipped: 4 active subgoals already in backlog)')
setf('plannerOutcome', po, 'LOG')

# ---- analysis outcome (LOG lines 4126-4173; carried until the analysis agent runs again) ----
ana = open(f'{S}/F0-analysis-response.txt').read()
findings = []
for m in re.finditer(r'^- \[(\w+)\] (\w+) :: (.*)$', ana, re.M):
    findings.append({'severity': m.group(1), 'type': m.group(2), 'observation': m.group(3).strip(), 'path': '(n/a)'})
hyps = re.search(r'Hypotheses:\n((?:- .*\n)+)', ana)
hypotheses = [l[2:].strip() for l in hyps.group(1).splitlines()] if hyps else []
msg = re.search(r'^Message: (.*)$', ana, re.M).group(1).strip()
tmplA = json.load(open(f'{S}/template-lastAnalysisOutcome.json'))
anaOut = {'findings': findings, 'hypotheses': hypotheses, 'proposedExecutableSubGoals': [], 'goalCriteria': {},
          'message': msg, 'goalKind': tmplA.get('goalKind'), 'possiblyComplete': False, 'injectionThrottle': False}
setf('analysisOutcome', anaOut, 'LOG (run analysis at 09:50:08; the block the next prompt rendered came from it; proposed subgoals omitted, all were skipped as similar)')
setf('lastAnalysisOutcome', anaOut, 'LOG')

# ---- problems (TEMPLATE shape, LOG values) ----
def problem(pid, title, desc, kws, first, last, created, updated):
    return {'id': pid, 'status': 'OPEN', 'severity': 'WARNING', 'title': title, 'description': desc, 'keywords': kws,
            'targetGoalId': ROOTGOAL, 'targetSubGoalId': SUB, 'firstSeenCycleId': first, 'lastSeenCycleId': last,
            'createdAt': created, 'updatedAt': updated, 'observationCycles': [], 'observationNotes': []}
P1 = f'problem::{ROOTGOAL}::{SUB}::eval:REJECTED::'
P2 = f'problem::{ROOTGOAL}::{SUB}::signal:LOW_CONFIDENCE::'
pc = {'open': [problem(P1, 'Evaluation: REJECTED', FEEDBACK, ['source:evaluation', 'eval:REJECTED'], 'cycle-1789285691360', CYCLE, '2026-09-13T07:49:36.000000000Z', '2026-09-13T07:58:57.750000000Z'),
              problem(P2, 'Signal: LOW_CONFIDENCE', 'Detected strong signal LOW_CONFIDENCE strength=3.0', ['source:signal', 'signal:LOW_CONFIDENCE'], 'cycle-1789285691360', CYCLE, '2026-09-13T07:49:36.000000000Z', '2026-09-13T07:58:57.750000000Z')],
      'resolved': []}
ppc = copy.deepcopy(pc)
for p in ppc['open']: p['lastSeenCycleId'] = PREV; p['updatedAt'] = '2026-09-13T07:56:33.700000000Z'
setf('problemContext', pc, 'LOG (next prompt PROBLEM CONTEXT: 2 open, same ids; timestamps approximate)')
setf('previousProblemContext', ppc, 'LOG')

# ---- signals at the end of the cut cycle (LOG: F3 cycle-start snapshot and the F3 ACTIVE SIGNALS block) ----
def key(name):
    # bare name: the key form SignalTypeKey writes and reads (the legacy wrapped form is also read)
    return name
def req(name, urgency, source, sub, age, valid, meta=None):
    return {'kind': {'name': name}, 'urgency': urgency, 'source': source, 'subGoalId': sub, 'age': age, 'validForCycles': valid, 'meta': meta or {}}
reqs = {
    key('LOW_CONFIDENCE'): req('LOW_CONFIDENCE', 3.0, 'evaluation', SUB, 0, 7),
    key('STAGNATION'): req('STAGNATION', 1.0, 'evaluation', None, 0, 5),
    key('MISSING_SYMBOL'): req('MISSING_SYMBOL', 3.0, 'planner-grounding:BookController', None, 0, 4, {'sources': ['planner-grounding:BookController', 'planner-grounding:MemberController']}),
    key('REPLAN_REQUESTED'): req('REPLAN_REQUESTED', 1.0, 'subgoal-scope-guard', None, 0, 19),
    key('DIVERGENCE_REQUEST'): req('DIVERGENCE_REQUEST', 0.7, 'interpretation', SUB, 0, 7),
}
setf('requests', {'open': reqs}, 'LOG F3 cycle-start snapshot + ACTIVE SIGNALS block (ages/validity approximate; EVOLUTION_OCCURRED and EVOLUTION_FINGERPRINT_CHANGED are derived, not stored)')
w = copy.deepcopy(T['windows'])
w['active']['evolution'].update({'boost': 3.0, 'age': 1, 'validForCycles': 59})
w['active']['evaluation'].update({'boost': 3.0, 'age': 0, 'validForCycles': 59})
w['active']['planner'].update({'boost': 3.0, 'age': 0, 'validForCycles': 59, 'source': 'cycle-start'})
w['active']['analysis'].update({'boost': 1.2, 'age': 3})
w['active']['merge'].update({'boost': 1.0, 'age': 4, 'validForCycles': 19, 'source': 'reinforce'})
setf('windows', w, 'LOG F3 ACTIVE SIGNALS block (ROLE_* strengths; ages approximate)')

# ---- recomputed at the next tick by CycleContextCoordinator: left as TEMPLATE, noted ----
for k in ('previousOutput', 'projectSummary', 'projectDiff', 'currentGoalDescription', 'currentSubGoalDescription', 'remainingGoals', 'completedGoals',
          'abandonedGoals', 'goalDepth', 'seedCoverage', 'currentSubGoalCriteria', 'cumulativeCriteriaStatus', 'criteriaConsecutiveFailCount',
          'lastAcceptedSubgoalScore', 'subgoalScoreHistory', 'relatedAbandonedGoals', 'planRationale', 'establishedBasePackage', 'outputPath',
          'lastCriteriaPassCount', 'priorRunEndReport'):
    prov[k] = 'TEMPLATE (recomputed by the coordinator at the next tick)'
prov['scoreHistoryByFile'] = 'TEMPLATE (per-file history of the template tree; same package layout)'
prov['recentDeltas'] = 'TEMPLATE'

out_dir = f'{ROOT}/data/firestarter/checkpoint-archive/{CYCLE}'
assert os.path.isdir(out_dir), out_dir
json.dump(C, open(f'{out_dir}/__context.json', 'w'), indent=2, ensure_ascii=False)
provenance = ("RECONSTRUCTED 2026-09-14, not captured. Carried context of run 2026-09-13_09-44-03 at " + CYCLE +
              " rebuilt from autobuilder-2026-09-13_09-44-38.log and behaviour-2026-09-13_09-44-45.ndjson by "
              "scripts/reconstruct_carried_context.py; the streak map is RE-KEYED from the "
              "old build's name key to name|subject; fields the coordinator recomputes are copied from "
              "mutations/cycle-1789409469209/meta.json of the 2026-09-14 rewind run. Field manifest: __context.provenance.json\n")
open(f'{out_dir}/__context.provenance', 'w').write(provenance)
json.dump(prov, open(f'{out_dir}/__context.provenance.json', 'w'), indent=1)
from collections import Counter
print(Counter(v.split(' ')[0] for v in prov.values()))
print('written', out_dir, os.path.getsize(f'{out_dir}/__context.json'), 'bytes; files parsed', len(files), 'findings', len(findings), 'hypotheses', len(hypotheses))
