"""T4-DY: when would core/stuck/Recurrence's ratio rule have flagged each BUILD grind, against the third occurrence
GrindDetector noticed it at?

Rebuilds each run's builds from its archive: build_fix_attempt events (COMPILE_OK / COMPILE_ERROR, in order) and,
for a failed one, the compiler block logged just before it, turned into GrindDetector's positional-free signatures
(file: message, with symbol and location for "cannot find symbol"). Missing-member signatures are not rebuilt, so a
notice in that wording reports no match.

usage: build_grind_ratio_replay.py [--failed-only] <run-archive dir> <run> [<run> ...]
  --failed-only  count only failed builds in the share (a clean build already ends a BUILD grind in the ledger)
"""
import gzip, json, re, sys, datetime as dt
args = sys.argv[1:]
FAILED_ONLY = '--failed-only' in args
args = [a for a in args if a != '--failed-only']
ARCH = args[0]; runs = args[1:]
TS = re.compile(r'^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)')
ERR = re.compile(r'([A-Za-z0-9_]+\.java):\[\d+,\d+]\s*(.+)$')
GRIND = re.compile(r'GRIND -- the same failure has now happened in (\d+) cycles \(since (\S+)\): (.+)$')
def pts(s): return dt.datetime.strptime(s[:23], '%Y-%m-%d %H:%M:%S.%f')
def events(path):
    t = gzip.open(path, 'rt').read(); d = json.JSONDecoder(); i = 0
    while i < len(t):
        while i < len(t) and t[i].isspace(): i += 1
        if i >= len(t): break
        o, i = d.raw_decode(t, i); yield o
SHARE, SPAN = 2/3, 0.25
for r in runs:
    evs = list(events(f'{ARCH}/{r}/behaviour.ndjson.gz'))
    start = dt.datetime.fromisoformat(evs[0]['timestamp']).replace(tzinfo=None)
    builds = []  # (time, cycle, outcome)
    for e in evs:
        if e.get('event') == 'build_fix_attempt' and e['payload'].get('outcome') in ('COMPILE_OK', 'COMPILE_ERROR'):
            builds.append([dt.datetime.fromisoformat(e['timestamp']).replace(tzinfo=None), e.get('cycleId'), e['payload']['outcome'], set()])
    # error blocks: consecutive ERROR lines after one timestamped line
    blocks = []; cur = None; last = None; lines = gzip.open(f'{ARCH}/{r}/autobuilder.log.gz', 'rt').read().splitlines()
    grinds = []
    for k, ln in enumerate(lines):
        m = TS.match(ln)
        if m:
            last = pts(m.group(1)); cur = None
            g = GRIND.search(ln)
            if g and int(g.group(1)) == 3: grinds.append((last, g.group(3)))
            continue
        em = ERR.search(ln)
        if em and last:
            if cur is None: cur = (last, set()); blocks.append(cur)
            msg = em.group(2).strip()
            if msg == 'cannot find symbol':
                sym = next((re.search(r'symbol:\s*(.+)$', x).group(1).strip() for x in lines[k+1:k+4] if 'symbol:' in x), None)
                loc = next((re.search(r'location:\s*(.+)$', x).group(1).strip() for x in lines[k+1:k+4] if 'location:' in x), None)
                if sym: msg += re.sub(r'\b(?:[a-z_]\w*\.)+([A-Z][\w$]*)', r'\1', ' ' + sym + ('' if loc is None else ' in ' + loc))
            cur[1].add(f'the build fails: {em.group(1)}: {msg}')
    for b in builds:
        if b[2] != 'COMPILE_ERROR': continue
        cand = [bl for bl in blocks if bl[0] <= b[0] and (b[0] - bl[0]).total_seconds() < 120]
        if cand: b[3] = cand[-1][1]
    print(f'== {r} builds={len(builds)} failed={sum(b[2]=="COMPILE_ERROR" for b in builds)} with-sigs={sum(bool(b[3]) for b in builds)} grinds@3={len(grinds)}')
    for gt, gsig in grinds:
        key = gsig if any(gsig in b[3] for b in builds) else None
        if key is None: print(f'  {gt:%H:%M:%S} fixed-3  (no reconstructed match) {gsig[:90]}'); continue
        first = next(i for i, b in enumerate(builds) if key in b[3]); fire = None
        for j in range(first, len(builds)):
            if key not in builds[j][3]: continue
            since = builds[first:j+1]
            if FAILED_ONLY: since = [b for b in since if b[2] == 'COMPILE_ERROR' and b[3]]
            same = sum(key in b[3] for b in since)
            span = (builds[j][0] - builds[first][0]).total_seconds(); el = max(1, (builds[j][0] - start).total_seconds())
            if same / len(since) >= SHARE and span / el >= SPAN: fire = builds[j][0]; break
        d = '' if fire is None else f'{(fire-gt).total_seconds()/60:+.1f} min'
        print(f'  {gt:%H:%M:%S} fixed-3 (min {(gt-start).total_seconds()/60:.0f})  ratio {"NEVER" if fire is None else f"{fire:%H:%M:%S}"} {d}  sightings={sum(key in b[3] for b in builds)}/{len(builds)-first} builds, failed-since-first={sum(1 for b in builds[first:] if b[2]=='COMPILE_ERROR')}  {key[:110]}')
