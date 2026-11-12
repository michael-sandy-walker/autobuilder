#!/usr/bin/env python3
"""T4-EU: what happened after each diagnosis request in the kept behaviour logs.

Per request (first per source and signature in a log): the cycles that ran after it, in how many of them the
same thing came back, and whether the log reaches GOAL_FULFILLED afterwards -- and after a recurrence.
The closure-refusal predicate is exact (the refusal's reason against later closure_refused events; checked on
data/pantry-rev3, 2 recurring cycles). For the other sources it is a text match of the signature's first 50
characters in later events, which is loose: read those rows as an upper bound.

Run from the repository root: python3 scripts/diagnosis_request_aftermath.py | sort
"""
import json, glob, os, re, collections
dec = json.JSONDecoder()
def events(path):
    s = open(path, errors='replace').read(); i = 0; n = len(s)
    while i < n:
        while i < n and s[i].isspace(): i += 1
        if i >= n: break
        try: o, j = dec.raw_decode(s, i)
        except Exception:
            k = s.find('\n{', i+1)
            if k < 0: break
            i = k+1; continue
        i = j
        if isinstance(o, dict): yield o
norm = lambda t: re.sub(r'\s+', ' ', (t or '').strip())
files = sorted(glob.glob(os.path.expanduser('~/.autobuilder/run-archive/**/behaviour*.ndjson'), recursive=True) + glob.glob('data/*/logs/behaviour*.ndjson'))
which = collections.Counter()
for f in files:
    txt = open(f, errors='replace').read()
    if '"diagnosis_request"' not in txt: continue
    evs = list(events(f)); seen = set()
    for i, e in enumerate(evs):
        if e.get('event') != 'diagnosis_request': continue
        p = e['payload']; src = p['source']; sig = p['signature']
        if (src, sig) in seen or src not in ('CLOSURE_REFUSAL','BUILD','PROTECTED_WRITE','TEST_FAILURES'): continue
        seen.add((src, sig))
        after = evs[i+1:]
        if src == 'CLOSURE_REFUSAL':
            why = sig.split(' was refused: ', 1)[-1].rstrip('.')
            if why.endswith('..'): why = why[:-2]
            hits = [j for j, a in enumerate(after) if a.get('event') == 'closure_refused' and norm(a['payload'].get('reason'))[:len(why)] == why[:400]]
        else:
            core = sig.split(': ', 1)[-1][:50]
            hits = []
            for j, a in enumerate(after):
                if a.get('event') in ('diagnosis_request','stuck_question_asked','stuck_recurred','stuck_ended','llm_call'): continue
                if core in norm(json.dumps(a.get('payload', {}))):
                    hits.append(j); which[a.get('event')] += 1
        # distinct cycles with a recurrence
        cyc = []
        for j in hits:
            c = after[j].get('cycleId')
            if c not in cyc: cyc.append(c)
        total = sum(1 for a in after if a.get('event') == 'cycle_start')
        ful = [j for j, a in enumerate(after) if 'GOAL_FULFILLED' in json.dumps(a)]
        first = hits[0] if hits else None
        cyc_after_first = sum(1 for a in after[first:] if a.get('event') == 'cycle_start') if hits else 0
        print(f"{src:16} recur-cycles={len(cyc):2} cycles-after-request={total:2} cycles-after-1st-recurrence={cyc_after_first:2} fulfilled-later={bool(ful)} fulfilled-after-recurrence={bool(ful and hits and ful[0] > first)} {os.path.relpath(f)[5:60]}")
print(which)
