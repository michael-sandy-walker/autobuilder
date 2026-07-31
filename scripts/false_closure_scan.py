"""How many COMPLETED subgoals were closed on a criterion the run itself later contradicted."""
import json,os,re,sys
sys.path.insert(0,'scripts')
from clause_coverage import parses
ASSERT = re.compile(r'"([^"]*?(?:GET|POST|PUT|PATCH|DELETE)\s+/[^"]*?)"\s+—\s+(?:GET|POST|PUT|PATCH|DELETE)\s+\S+\s+→\s+(\d{3}),\s+but the running application answered (\d{3})')
def endpoint_status(text):
    m=re.search(r'\b(GET|POST|PUT|PATCH|DELETE)\s+(/[A-Za-z0-9_\-{}/.?=&]*)',text or '')
    s=re.search(r'\b(?:HTTP|status(?:\s+code)?|returns?|answers?|responds?\s+with|→)\s+(\d{3})\b',text or '',re.I)
    if not m or not s: return None
    return (m.group(1).upper(), m.group(2), s.group(1))
print(f"{'run':22}{'COMPLETED w/ exec crit':>24}{'contradicted claims':>21}{'FALSE CLOSURES':>16}")
tot_op=tot_fc=0
for d in sorted(sys.argv[1:]):
    d=d.rstrip('/'); gs=os.path.join(d,'GoalStack.json'); lg=os.path.join(d,'autobuilder.log')
    if not (os.path.exists(gs) and os.path.exists(lg)): continue
    try: tree=json.load(open(gs,errors='replace'))['goalTree']
    except Exception: continue
    done=[]
    def walk(n):
        if n.get('status')=='COMPLETED':
            for c in ((n.get('metadata') or {}).get('acceptanceCriteria') or []):
                if parses(str(c)): done.append((n.get('id'),str(c)))
        for c in n.get('subGoals') or []: walk(c)
    walk(tree)
    contradicted=set()
    with open(lg,errors='replace') as fh:
        for line in fh:
            if 'but the running application answered' not in line: continue
            for m in ASSERT.finditer(line):
                es=endpoint_status(m.group(1))
                if es: contradicted.add(es)
    fc=set()
    for gid,c in done:
        es=endpoint_status(c)
        if es and es in contradicted: fc.add((gid,es))
    if done:
        print(f"{os.path.basename(d):22}{len(done):>24}{len(contradicted):>21}{len(fc):>16}")
        tot_op+=len(done); tot_fc+=len(fc)
print(f"\npooled: {tot_fc} false closures over {tot_op} closures carrying an executable criterion")
