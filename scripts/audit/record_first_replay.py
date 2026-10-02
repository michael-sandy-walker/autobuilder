# record_first_replay.py <dir with pristine-<target>/ trees> -- every acceptance criterion of archived adopted runs,
# with the changes of the run's FINAL tree since its pristine tree (scripts/prepare_module_change.sh), as rows.tsv for
# RecordFirstReplay.java, which prints the record's answer (UnchangedClaims.answer) where it has one.
# Final tree, not decision time: a later change can contradict a claim true when it was judged (conservative).
import gzip, json, os, sys, tarfile, hashlib, io
arch=os.path.expanduser('~/.autobuilder/run-archive'); S=sys.argv[1]
def tree(root):
    out={}
    for d,_,fs in os.walk(root):
        for f in fs:
            p=os.path.join(d,f); r=os.path.relpath(p,root)
            if r.startswith('src/'): out[r]=hashlib.sha1(open(p,'rb').read()).hexdigest()
    return out
pr={t:tree(f'{S}/pristine-{t}') for t in ['receiver-questions','parameterized-rows','edit-hunks']}
def nodes(g):
    yield g
    for s in g.get('subGoals') or []: yield from nodes(s)
rows=[]
for r in sorted(os.listdir(arch)):
    A=f'{arch}/{r}'
    if not os.path.exists(f'{A}/target.txt') or os.path.exists(f'{A}/INVALID.txt'): continue
    t=open(f'{A}/target.txt').read().split('=')[-1].split()[0]
    if t not in pr or not os.path.exists(f'{A}/artifact-src.tar.gz') or not os.path.exists(f'{A}/GoalStack.json.gz'): continue
    fin={}
    with tarfile.open(f'{A}/artifact-src.tar.gz') as tf:
        for m in tf.getmembers():
            if not m.isfile(): continue
            n=m.name; i=n.find('src/')
            if i<0: continue
            fin[n[i:]]=hashlib.sha1(tf.extractfile(m).read()).hexdigest()
    p=pr[t]
    mod=[k for k in fin if k in p and fin[k]!=p[k]]; add=[k for k in fin if k not in p]; rem=[k for k in p if k not in fin]
    try: d=json.load(gzip.open(f'{A}/GoalStack.json.gz'))
    except Exception: continue
    root=d.get('goalTree') or {}
    goal=(root.get('description') or '').replace('\t',' ').replace('\n',' ')
    for g in nodes(root):
        st=g.get('status'); desc=(g.get('description') or '').replace('\t',' ').replace('\n',' ')
        for c in (g.get('metadata') or {}).get('acceptanceCriteria') or []:
            if isinstance(c,str): rows.append('\t'.join([r,st or '',c.replace('\t',' ').replace('\n',' '),goal+' '+desc,','.join(mod),','.join(add),','.join(rem)]))
open(f'{S}/rows.tsv','w').write('\n'.join(rows)); print(len(rows),'criteria', file=sys.stderr)
