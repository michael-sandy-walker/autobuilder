"""T4-EQ check: which call of a record is the claim's answer -- today's rule beside 'last calls whose result no later call uses'."""
import json, re, sys
from collections import Counter

def vtype(v):
    m = re.match(r'^([A-Z][A-Za-z0-9_]*)(\[|$)', v or '')
    return m.group(1) if m else None

def etype(entry):
    return entry.rsplit('.', 1)[0]

def terminal(calls):
    out = []
    for i, c in enumerate(calls):
        v = c.get('value') or ''
        used = False
        if c['outcome'] == 'returned' and v:
            t = vtype(v)
            for d in calls[i + 1:]:
                if any(v == a or (len(v) > 3 and v.strip('"') and v.strip('"') in a) for a in (d.get('args') or [])):
                    used = True; break
                if t and etype(d['entry']).split('.')[-1] == t:
                    used = True; break
        if not used: out.append(c)
    return out

def today(j, calls):
    e = (j.get('byFeature') or {}).get('entry')
    through = [c for c in calls if c['entry'] == e] if e else []
    return through or calls

def pattern(calls):
    p = []
    for c in calls:
        given = []
        for a in c.get('args') or []:
            given.append(a)
            if len(a) > 2 and a.startswith('"') and a.endswith('"'): given.append(a[1:-1])
        given = sorted([g for g in given if len(g) >= 2 and g != 'null'], key=len, reverse=True)
        ans = 'returns nothing' if c['outcome'] == 'done' else c['outcome'] + ' ' + (c.get('value') or '')
        for g in given: ans = ans.replace(g, '<argument>')
        l = c['entry'] + ' ' + re.sub(r'\d+', '<n>', ans)
        if l not in p: p.append(l)
    return tuple(p)

def said(calls):
    s = []
    for c in calls:
        x = '%s(%s) %s %s' % (c['entry'], ', '.join(a[:30] for a in c.get('args') or []), c['outcome'], (c.get('value') or '')[:50])
        if x not in s: s.append(x)
    return s

verbose = '-v' in sys.argv
for path in [a for a in sys.argv[1:] if a != '-v']:
    a = json.load(open(path))
    kept = [j for j in a['claims'] if j['verdict'] == 'anchored']
    changed = 0; n_today = Counter(); n_cand = Counter(); pt = Counter(); pc = Counter(); rows = []
    for j in kept:
        calls = [c for o in j['observations'] if o['variant'] == 'as-is' for c in (o.get('calls') or [])]
        t, c = today(j, calls), terminal(calls)
        et, ec = sorted({x['entry'] for x in t}), sorted({x['entry'] for x in c})
        n_today[len(et)] += 1; n_cand[len(ec)] += 1
        pt[pattern(t)] += 1; pc[pattern(c)] += 1
        if et != ec:
            changed += 1
            rows.append((j['claim']['id'], j['claim']['text'], (j.get('byFeature') or {}).get('entry'), len(calls), said(t), said(c)))
    grouped = lambda p: sum(v for v in p.values() if v > 1)
    print('%s: kept %d | answer entries differ for %d | entries per claim today %s, candidate %s | claims sharing an answer with another: today %d (%d groups), candidate %d (%d groups)' % (
        path.split('/')[-2], len(kept), changed, dict(sorted(n_today.items())), dict(sorted(n_cand.items())),
        grouped(pt), sum(1 for v in pt.values() if v > 1), grouped(pc), sum(1 for v in pc.values() if v > 1)))
    if verbose:
        for r in rows:
            print('\n %s (%d calls, judged through %s): %s' % (r[0], r[3], r[2], r[1][:230]))
            print('   today:     ' + ' | '.join(r[4])[:330])
            print('   candidate: ' + ' | '.join(r[5])[:330])
