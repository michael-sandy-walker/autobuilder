"""T6-I (T4-CT): the pair measure for the stuck ledger's use of earlier runs. Per archived run: terminal, smoke,
minutes, builds, the stuck_prior_consulted events, and per BUILD signature seen in two or more builds: failed
builds carrying it, how many came before its answer was delivered, and minutes from first sighting to ENDED.
Builds and signatures are rebuilt by scripts/build_grind_ratio_replay.py.

usage: t6i_stuck_pairs.py <run> [<run> ...]   (runs under ~/.autobuilder/run-archive)
"""
import sys, os, json, gzip, runpy, io, contextlib, datetime as dt
ARCH = os.path.expanduser('~/.autobuilder/run-archive')
def events(r):
    t = gzip.open(f'{ARCH}/{r}/behaviour.ndjson.gz', 'rt').read(); d = json.JSONDecoder(); i = 0
    while i < len(t):
        while i < len(t) and t[i].isspace(): i += 1
        if i >= len(t): break
        o, i = d.raw_decode(t, i); yield o
def ts(s): return dt.datetime.fromisoformat(s).replace(tzinfo=None)
for r in sys.argv[1:]:
    sys.argv = ['x', ARCH, r]
    with contextlib.redirect_stdout(io.StringIO()):
        g = runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'build_grind_ratio_replay.py'))
    builds = g['builds']
    evs = list(events(r))
    summ = open(f'{ARCH}/{r}/summary.txt').read()
    term = next((l.split('=', 1)[1] for l in summ.splitlines() if l.startswith('terminal=')), '?')
    smoke = next((l.split('=', 1)[1] for l in summ.splitlines() if l.startswith('smoke=')), '?')
    start = ts(evs[0]['timestamp']); end = ts(evs[-1]['timestamp'])
    consulted = [e['payload'] for e in evs if e.get('event') == 'stuck_prior_consulted']
    delivered = {}; ended = {}
    for e in evs:
        p = e.get('payload', {})
        if e.get('event') == 'stuck_question_asked' and p.get('answered') and p.get('source') == 'BUILD':
            delivered.setdefault(p['signature'], ts(e['timestamp']))
        if e.get('event') == 'stuck_ended' and p.get('source') == 'BUILD':
            ended.setdefault(p['signature'], ts(e['timestamp']))
    fails = sum(b[2] == 'COMPILE_ERROR' for b in builds)
    print(f'== {r} {term} {smoke} minutes={(end-start).total_seconds()/60:.0f} builds={len(builds)} failed={fails} consulted={len(consulted)}')
    for c in consulted: print(f'   consulted: {c["signature"][:90]} priorRuns={c["priorRuns"]} ended={c["priorEndedRuns"]} startRung={c["startRung"]}')
    sigs = set(delivered) | {s for b in builds for s in b[3] if sum(s in x[3] for x in builds) >= 2}
    for s in sorted(sigs):
        first = next((b[0] for b in builds if s in b[3]), None)
        dl = delivered.get(s)
        before = sum(1 for b in builds if s in b[3] and (dl is None or b[0] <= dl))
        total = sum(1 for b in builds if s in b[3])
        en = ended.get(s)
        span = '' if first is None or en is None else f'{(en-first).total_seconds()/60:.1f}'
        print(f'   {s[:90]}  failed-with-it={total} before-delivery={before if dl else "no delivery"} first->ended-min={span or "-"}')
