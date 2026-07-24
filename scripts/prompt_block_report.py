#!/usr/bin/env python3
"""T4-AP Phase B instrument: per-block prompt composition report over run logs.

Extracts every [USR] prompt from one or more autobuilder logs, classifies each
prompt by the agent/consumer that produced it (content-shape based — immune to
retry-induced ordering drift against the behaviour telemetry), segments it into
named blocks (``--- HEADER ---`` sections plus known unheadered openers), and
aggregates fires x estimated tokens per (consumer, block).

Usage:
    python3 scripts/prompt_block_report.py <autobuilder.log> [more logs...]

Optionally set OUT=<path> to also dump the per-call rows as JSON.

Token estimate is chars/4 (same family as PromptTokenBudget); real per-call
prompt tokens live in the behaviour ndjson ``llm_call`` events when a precise
per-role total is needed — this report's value is the per-BLOCK attribution,
which the telemetry does not carry.
"""
import json
import os
import re
import sys
from collections import defaultdict

LOGLINE = re.compile(r'^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+ \[')
HEADER = re.compile(r'^\s*--- ([A-Z][A-Z0-9 ()/\-]+?) ---\s*$')

OPENERS = [
    ('Current Goal:', 'GOAL_HEADER'),
    ('Remaining Subgoals:', 'REMAINING_SUBGOALS'),
    ('Completed Subgoals:', 'COMPLETED_SUBGOALS'),
    ('Structure Summary:', 'STRUCTURE_SUMMARY'),
    ('Diff Summary (Last Accepted vs Current):', 'DIFF_SUMMARY'),
    ('Last raw evolution agent response:', 'LAST_RAW_RESPONSE'),
    ('Repetition Detected:', 'REPETITION_DIRECTIVE'),
    ('Divergence Mode Triggered:', 'DIVERGENCE_DIRECTIVE'),
    ('Escalated Divergence Required:', 'ESCALATED_DIVERGENCE'),
    ('Score Plateau Detected:', 'SCORE_PLATEAU'),
    ('Respond with:', 'RESPONSE_FORMAT_FOOTER'),
    ('Respond using this exact format:', 'RESPONSE_FORMAT_FOOTER'),
    ('ROOT GOAL INVARIANTS:', 'ROOT_GOAL_INVARIANTS'),
    ('Evaluate the recent mutation', 'EVAL_PREAMBLE'),
    ('NOTE:', 'EMPTY_BACKLOG_NOTE'),
    ('CriteriaCheck:', 'CRITERIA_CHECK_FORMAT'),
    ('REQUIREMENTS:', 'REQUIREMENTS_LIST'),
    ('For EACH requirement', 'REQUIREMENTS_FOOTER'),
    ('=== Recent Artifacts', 'ARTIFACT_HISTORY_ALL'),
    ('Analyse the following cycle history', 'INTERP_PREAMBLE'),
]


def classify(prompt):
    """Attribute a prompt to its consumer by content shape."""
    p = prompt.lstrip()
    if p.startswith('Evaluate the recent mutation'):
        return 'evaluation'
    if p.startswith('Analyse the following cycle history'):
        return 'interpretation'
    if 'ProposedExecutableSubGoals' in prompt:
        return 'analysis'
    if '--- RESPONSE FORMAT ---' in prompt and '#SubGoalAppend' in prompt:
        return 'planner'
    if 'FOCUSED TEST-REPAIR MODE' in prompt:
        return 'evolution:test-repair'
    if 'Do not return unchanged files.' in prompt:
        return 'evolution'
    if 'REQUIREMENTS:' in prompt and 'For EACH requirement' in prompt:
        return 'aux:requirement-grounding'
    if '--- CURRENT SUBGOAL ---' in prompt and '--- TASK ---' in prompt:
        return 'aux:scope-classifier'
    return 'aux:other'


def extract_usr_prompts(logpath):
    prompts = []
    with open(logpath, errors='replace') as f:
        lines = f.readlines()
    i = 0
    while i < len(lines):
        if '[USR]' in lines[i] and LOGLINE.match(lines[i]):
            j = i + 1
            if j < len(lines) and lines[j].startswith('-----'):
                j += 1
            buf = []
            while j < len(lines) and not LOGLINE.match(lines[j]):
                buf.append(lines[j])
                j += 1
            prompts.append((i + 1, ''.join(buf)))
            i = j
        else:
            i += 1
    return prompts


def segment(prompt):
    """Split a prompt into (block_name, text) pairs. Lines inside the
    interpretation artifact history (whose inner '--- type ---' headers are
    lowercase and thus don't match HEADER) stay within one block."""
    blocks = []
    cur_name, cur = 'PREAMBLE', []
    for line in prompt.split('\n'):
        name = None
        m = HEADER.match(line)
        if m:
            name = m.group(1).strip()
        else:
            for opener, n in OPENERS:
                if line.startswith(opener):
                    name = n
                    break
        if name:
            if cur:
                blocks.append((cur_name, '\n'.join(cur)))
            cur_name, cur = name, [line]
        else:
            cur.append(line)
    if cur:
        blocks.append((cur_name, '\n'.join(cur)))
    return blocks


def est_tokens(text):
    return max(1, len(text) // 4)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    agg = defaultdict(lambda: [0, 0])
    role_prompts = defaultdict(list)
    percall_rows = []
    for logpath in sys.argv[1:]:
        for line_no, prompt in extract_usr_prompts(logpath):
            role = classify(prompt)
            tok = est_tokens(prompt)
            role_prompts[role].append(tok)
            blocks = segment(prompt)
            for b, t in blocks:
                agg[(role, b)][0] += 1
                agg[(role, b)][1] += est_tokens(t)
            percall_rows.append({'log': os.path.basename(logpath), 'line': line_no,
                                 'role': role, 'est_tokens': tok,
                                 'blocks': {b: est_tokens(t) for b, t in blocks}})

    print("== per-consumer prompt counts (est tokens) ==")
    grand = 0
    for r, toks in sorted(role_prompts.items()):
        toks.sort()
        grand += sum(toks)
        print(f"  {r:26s} n={len(toks):4d}  median={toks[len(toks) // 2]:6d}  "
              f"min={toks[0]:6d}  max={toks[-1]:6d}  total={sum(toks):8d}")
    print(f"  {'TOTAL':26s} {'':56s}total={grand:8d}")

    print("\n== block inventory (consumer | block | fires | est-token total | avg) ==")
    for (r, b), (c, t) in sorted(agg.items(), key=lambda kv: -kv[1][1]):
        if t < 300:
            continue
        print(f"  {r:26s} | {b:38s} | {c:4d} | {t:8d} | {t // c:6d}")

    out = os.environ.get('OUT')
    if out:
        json.dump(percall_rows, open(out, 'w'))
        print(f"\nper-call rows written to {out}")


if __name__ == '__main__':
    main()
