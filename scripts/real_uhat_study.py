#!/usr/bin/env python3
"""Reproducible real-UHAT pilot and gate ablation; one checkpoint per seed."""
from __future__ import annotations
import argparse
import hashlib
import itertools
import json
import os
from pathlib import Path
import random
import subprocess
import sys
import time
import traceback

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
TASKS = ('ends_a', 'ends_ab', 'subsequence_ab', 'repeat_final', 'all_a', 'since_a')
BASE = 'alphabet a b\nis_a = symbol a\nis_b = symbol b\nis_bos = bos\n'
BODIES = {
 'ends_a': 'accept = is_a',
 'ends_ab': 'prev_a = rightmost(true, is_a@j)\naccept = prev_a & is_b',
 'subsequence_ab': 'seen_a = rightmost(is_a@j, true)\nhit = seen_a & is_b\nprev_hit = rightmost(hit@j, true)\naccept = hit | prev_hit',
 'repeat_final': 'same = rightmost((is_a@i & is_a@j) | (is_b@i & is_b@j), true)\naccept = same',
 'all_a': 'seen_b = rightmost(is_b@j, true)\naccept = is_a & !seen_b',
 'since_a': 'last_other = rightmost(!(is_b@j), is_a@j)\naccept = is_b & last_other',
}

def predicate(task, w):
    if task == 'ends_a': return bool(w) and w[-1] == 'a'
    if task == 'ends_ab': return len(w)>1 and tuple(w[-2:]) == ('a','b')
    if task == 'subsequence_ab': return any(w[i]=='a' and 'b' in w[i+1:] for i in range(len(w)))
    if task == 'repeat_final': return bool(w) and w[-1] in w[:-1]
    if task == 'all_a': return bool(w) and all(c=='a' for c in w)
    if task == 'since_a': return bool(w) and w[-1]=='b' and any(w[i]=='a' and all(c=='b' for c in w[i+1:]) for i in range(len(w)-1))
    raise ValueError(task)

def digest(data): return hashlib.sha256(data).hexdigest()
def write(path, obj):
    path=Path(path); path.parent.mkdir(parents=True,exist_ok=True)
    tmp=path.with_suffix(path.suffix+'.tmp'); tmp.write_text(json.dumps(obj,indent=2)+'\n'); tmp.replace(path)
def words_upto(k): return [w for n in range(1,k+1) for w in itertools.product('ab',repeat=n)]
def dataset():
    words=words_upto(7); random.Random(20260911).shuffle(words)
    n=int(.8*len(words)); train, validation=words[:n], words[n:]
    rng=random.Random(20260912)
    bands={'audit_1_8':words_upto(8)}
    for lo,hi in ((9,16),(17,32),(33,64)):
        bands[f'test_{lo}_{hi}']=[tuple(rng.choice('ab') for _ in range(rng.randint(lo,hi))) for _ in range(256)]
    return train,validation,bands

def balanced_bands(task):
    """Targeted 128-positive/128-negative samples per band, with replacement.

    Rare classes are constructed explicitly. These are diagnostic distributions,
    not uniform random words or estimates of the natural class prevalence.
    """
    rng = random.Random(20260913 + TASKS.index(task))
    bands = {}
    for lo, hi in ((9, 16), (17, 32), (33, 64)):
        words = []
        for label in (False, True):
            for _ in range(128):
                n = rng.randint(lo, hi)
                while True:
                    w = [rng.choice('ab') for _ in range(n)]
                    if task == 'ends_a': w[-1] = 'a' if label else 'b'
                    elif task == 'ends_ab' and label: w[-2:] = ['a', 'b']
                    elif task == 'all_a' and label: w = ['a'] * n
                    elif task == 'repeat_final' and not label:
                        final = rng.choice('ab')
                        w = [('b' if final == 'a' else 'a')] * (n-1) + [final]
                    elif task == 'subsequence_ab' and not label:
                        split = rng.randint(0, n)
                        w = ['b'] * split + ['a'] * (n-split)
                    elif task == 'since_a':
                        if label: w[-1] = 'b'
                        elif rng.randrange(2): w = ['b'] * n
                        else: w[-1] = 'a'
                    if predicate(task, w) == label:
                        words.append(tuple(w))
                        break
        rng.shuffle(words)
        bands[f'balanced_{lo}_{hi}'] = words
    return bands

def plan(out):
    from uhat import brasp
    out.mkdir(parents=True,exist_ok=True)
    for task in TASKS:
        text=BASE+BODIES[task]+'\noutput accept\n'
        prog=brasp.parse(text)
        for w in words_upto(8):
            assert brasp.accepts(prog,w)==predicate(task,w),(task,w)
        path=out/'specs'/f'{task}.brasp'; path.parent.mkdir(exist_ok=True);path.write_text(text)
    cells=[]
    for task,width,(layers,heads),seed in itertools.product(TASKS,(6,12),((1,1),(2,1),(1,2)),range(5)):
        cells.append(dict(study='pilot',task=task,width=width,layers=layers,heads=heads,seed=seed,gate='learned_penalty'))
    for task,seed,gate in itertools.product(('ends_ab','repeat_final','since_a'),range(10),('fixed_one','learned','learned_penalty','fixed_zero')):
        cells.append(dict(study='gate',task=task,width=6,layers=1,heads=1,seed=seed,gate=gate))
    for i,c in enumerate(cells):
        c.update(id=f'{i:03d}_{c["study"]}_{c["task"]}_d{c["width"]}_l{c["layers"]}_h{c["heads"]}_s{c["seed"]}_{c["gate"]}',steps=1500,lr=.02,hard_fraction=.4,beta_end=32.,cap=20000,data_seed=20260911)
        c['spec_sha256']=digest((out/'specs'/f'{c["task"]}.brasp').read_bytes())
    (out/'manifest.jsonl').write_text(''.join(json.dumps(c)+'\n' for c in cells))
    tr,va,bands=dataset()
    write(out/'dataset.json',dict(train=tr,validation=va,bands=bands,notes='Nonempty words only; audit overlaps training/validation. Long bands are independent final tests.'))
    print(f'{len(cells)} cells: 180 pilot + 120 gate; 6 predicates validated on 510 words each')

def predictions(model,words,snap=None):
    from uhat.real_model import accepts
    return [a for start in range(0,len(words),32) for a in accepts(model,words[start:start+32],snap_to=snap)]

def run(args,c,out):
    import torch
    from uhat.real_model import RealUhat, RealUhatConfig, encode
    torch.set_num_threads(int(os.environ.get('OMP_NUM_THREADS','2')))
    torch.manual_seed(c['seed'])
    torch.use_deterministic_algorithms(True)
    model=RealUhat(RealUhatConfig(alphabet=('a','b'),width=c['width'],layers=c['layers'],heads=c['heads']))
    heads=[h for block in model.blocks for h in block.heads]
    if c['gate'].startswith('fixed'):
        for h in heads:
            h.gate.data.fill_(0. if c['gate']=='fixed_zero' else 1.);h.gate.requires_grad_(False)
    record=dict(c,status='training',python=sys.version,torch=torch.__version__,device='cpu',hostname=os.uname().nodename,job_id=os.environ.get('SLURM_JOB_ID'),data_sha256=digest((out/'dataset.json').read_bytes()),source_sha256=digest(Path(__file__).read_bytes()),dtype='float32',threads=torch.get_num_threads(),optimizer='Adam',checkpoint_rule='minimum hard validation BCE at every 50 steps and final step; earliest on ties',extractor_atol=0.0,extractor_default_rtol=0.0,score_group_tolerance=0.0)
    path=out/'records'/f'{c["id"]}.json';write(path,record)
    data=json.loads((out/'dataset.json').read_text());tr,va=data['train'],data['validation']
    x,lengths=encode(tr,('a','b'));vx,vl=encode(va,('a','b'))
    y=torch.tensor([predicate(c['task'],w) for w in tr],dtype=torch.float32)
    vy=torch.tensor([predicate(c['task'],w) for w in va],dtype=torch.float32)
    positives=y.sum().item();weight=torch.where(y>.5,(len(y)-positives)/max(positives,1),1.)
    opt=torch.optim.Adam(model.parameters(),lr=c['lr'])
    checkpoint=out/'checkpoints'/f'{c["id"]}.pt';checkpoint.parent.mkdir(exist_ok=True)
    best=float('inf');started=time.monotonic();history=[]
    for step in range(c['steps']):
        beta=1+(c['beta_end']-1)*step/max(1,c['steps']-1)
        loss=torch.nn.functional.binary_cross_entropy_with_logits(model(x,lengths,beta=beta,hard=step>=int(c['steps']*(1-c['hard_fraction']))),y,weight=weight)
        if c['gate']=='learned_penalty':loss=loss+.01*sum(torch.relu(h.gate) for h in heads)
        opt.zero_grad();loss.backward();opt.step()
        if (step+1)%50==0 or step+1==c['steps']:
            with torch.no_grad():
                logits=model(vx,vl,hard=True); val=torch.nn.functional.binary_cross_entropy_with_logits(logits,vy).item()
                acc=((logits>0)==(vy>0)).float().mean().item()
            history.append(dict(step=step+1,validation_bce=val,validation_accuracy=acc))
            if val<best:
                best=val;torch.save(dict(config=c,state_dict=model.state_dict(),selected_step=step+1),checkpoint)
    record.update(status='trained',train_seconds=time.monotonic()-started,validation_history=history,checkpoint_sha256=digest(checkpoint.read_bytes()))
    write(path,record)
    command=[sys.executable,str(Path(__file__).resolve()),'post','--out',str(out),'--cell',str(args.cell)]
    try:
        done=subprocess.run(command,timeout=args.post_timeout)
        if done.returncode:
            record=json.loads(path.read_text());record.update(status='post_error',returncode=done.returncode);write(path,record)
    except subprocess.TimeoutExpired:
        record=json.loads(path.read_text());record.update(status='post_timeout',post_timeout=args.post_timeout);write(path,record)

def post(c,out):
    import torch
    from uhat import brasp
    from uhat.real_model import RealUhat,RealUhatConfig
    from uhat.real_extract import class_tables,build_program,ExtractionError,score_levels
    torch.set_num_threads(int(os.environ.get('OMP_NUM_THREADS','2')))
    path=out/'records'/f'{c["id"]}.json';record=json.loads(path.read_text())
    saved=torch.load(out/'checkpoints'/f'{c["id"]}.pt',map_location='cpu',weights_only=False)
    model=RealUhat(RealUhatConfig(alphabet=('a','b'),width=c['width'],layers=c['layers'],heads=c['heads']));model.load_state_dict(saved['state_dict']);model.eval()
    record.update(selected_step=saved['selected_step'],gates=[float(torch.relu(h.gate)) for b in model.blocks for h in b.heads])
    data=json.loads((out/'dataset.json').read_text())
    sets={'train':data['train'],'validation':data['validation'],**data['bands'],**balanced_bands(c['task'])}
    ordinary={name:predictions(model,ws) for name,ws in sets.items()}
    record['ordinary_accuracy']={name:sum(a==predicate(c['task'],w) for a,w in zip(ordinary[name],ws))/len(ws) for name,ws in sets.items()}
    record.update(status='extracting');write(path,record)
    started=time.monotonic()
    try: tables=class_tables(model,cap=c['cap'])
    except ExtractionError as e:
        record.update(status='extraction_cap',error=str(e),closure_seconds=time.monotonic()-started);write(path,record);return
    record['closure_seconds']=time.monotonic()-started;record['classes']=[len(t.values) for t in tables]
    sizes=[3]+record['classes'][:-1]
    record['candidate_tuples']=[v*(v+1)**c['heads'] for v in sizes]
    levels=[];values=model.embedding.weight.detach()
    for b,t in zip(model.blocks,tables):
        levels.append([max(map(len,score_levels(h,values,values))) for h in b.heads]);values=t.values
    record['max_score_levels_per_head']=levels;write(path,record)
    started=time.monotonic();program=build_program(model,cap=c['cap'])
    record['program_emission_seconds_including_reclosure']=time.monotonic()-started
    rendered=brasp.render(program);pp=out/'programs'/f'{c["id"]}.brasp';pp.parent.mkdir(exist_ok=True);pp.write_text(rendered)
    record.update(status='evaluating',program_sha256=digest(rendered.encode()),program_nodes=len(program.subprograms));write(path,record)
    results={}
    for name,ws in sets.items():
        snapped=predictions(model,ws,[t.values for t in tables]);extracted=[brasp.accepts(program,w) for w in ws]
        labels=[predicate(c['task'],w) for w in ws]
        results[name]=dict(n=len(ws),unique_words=len(set(map(tuple,ws))),positive=sum(labels),ordinary_snapped_disagreements=sum(a!=b for a,b in zip(ordinary[name],snapped)),snapped_program_disagreements=sum(a!=b for a,b in zip(snapped,extracted)),ordinary_program_disagreements=sum(a!=b for a,b in zip(ordinary[name],extracted)),program_accuracy=sum(a==b for a,b in zip(extracted,labels))/len(ws))
        record['evaluations']=results;write(path,record)
    record.update(status='evaluated');write(path,record)

def main():
    p=argparse.ArgumentParser();p.add_argument('command',choices=['plan','run','post','summary']);p.add_argument('--out',type=Path,required=True);p.add_argument('--cell',type=int,default=0);p.add_argument('--post-timeout',type=int,default=1800);p.add_argument('--steps',type=int)
    args=p.parse_args();out=args.out.resolve()
    if args.command=='plan':return plan(out)
    if args.command=='summary':
        from collections import Counter
        rows=[json.loads(p.read_text()) for p in (out/'records').glob('*.json')];print(json.dumps(dict(records=len(rows),statuses=dict(Counter(r['status'] for r in rows))),indent=2));return
    c=json.loads((out/'manifest.jsonl').read_text().splitlines()[args.cell])
    if args.steps:c['steps']=args.steps
    try:
        if args.command=='run':run(args,c,out)
        else:post(c,out)
    except Exception:
        path=out/'records'/f'{c["id"]}.json';record=json.loads(path.read_text()) if path.exists() else c
        record.update(status='error',error=traceback.format_exc());write(path,record);raise
if __name__=='__main__':main()
