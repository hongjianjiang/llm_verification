#!/usr/bin/env python3
"""Fixed-grid, randomized-order structural ablation. Records every repetition."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import re
import signal
import subprocess
import sys
import tempfile
import time
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))

def command(args,timeout):
    start=time.monotonic();p=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,start_new_session=True)
    try: output,_=p.communicate(timeout=max(.01,timeout));return p.returncode,time.monotonic()-start,output
    except subprocess.TimeoutExpired:
        os.killpg(p.pid,signal.SIGKILL);output,_=p.communicate();return -999,time.monotonic()-start,output

def plan(out):
    from ltl2_generator.ast import Letter,Once,AtJ,AtI,Yst,Hist,Since,BOS,and1,or1,andb,orb,not1
    from ltl2_generator.print import brasp_ltl
    from brasp_families import marks_program,marked_same_program
    out.mkdir(parents=True,exist_ok=True);(out/'inputs').mkdir(exist_ok=True)
    cells=[]
    for k in (2,8,32):
        alphabet=['a','b'];prefix=Letter('a')
        for i in range(1,k):prefix=and1(Letter(alphabet[i%2]),Once(AtJ(prefix)))
        close=Letter('a')
        for _ in range(k):close=Yst(AtJ(close))
        formulas={'dot':(or1(prefix,Once(AtJ(prefix))),alphabet),'Y':(close,alphabet),'no2a':(Hist(AtJ(not1(and1(Letter('a'),close)))),alphabet)}
        letters=[f's{i}' for i in range(k)]
        same=orb(*[andb(AtI(Letter(a)),AtJ(Letter(a))) for a in letters])
        smaller=orb(*[andb(AtI(Letter(letters[i])),AtJ(Letter(letters[j]))) for i in range(k) for j in range(i)])
        formulas.update(slb=(Once(same),letters),mono=(Hist(smaller),letters),since=(Since(same,AtJ(Letter('marker'))),['marker']+letters))
        for name,(formula,abc) in formulas.items():
            path=out/'inputs'/f'{name}_{k}.ltl';path.write_text(brasp_ltl(formula,abc))
            cells.append(dict(family=name,parameter=k,input=path.name,expected='empty' if name=='mono' else 'nonempty',bos_policy='BOS included in strict past; mono rejects because its symbol comparison excludes BOS.'))
        for name,text in [('marks',marks_program(k)),('same',marked_same_program(k,2))]:
            path=out/'inputs'/f'{name}_{k}.brasp';path.write_text(text);cells.append(dict(family=name,parameter=k,input=path.name,expected='nonempty'))
    for c in cells:c['sha256']=hashlib.sha256((out/'inputs'/c['input']).read_bytes()).hexdigest()
    (out/'manifest.jsonl').write_text(''.join(json.dumps(c)+'\n' for c in cells));print(len(cells),'instances, 4 routes x 3 repetitions =',len(cells)*12,'measurements')

def run(a):
    c=json.loads((a.out/'manifest.jsonl').read_text().splitlines()[a.cell]);rows=[]
    path=(a.out/'inputs'/c['input']).resolve();jar=str(a.jar.resolve());abc=str(a.abc.resolve())
    dest=a.out/'records';dest.mkdir(exist_ok=True);logs=a.out/'logs';logs.mkdir(exist_ok=True)
    rng=random.Random(20260911+a.cell)
    for rep in range(3):
        routes=['dfa','full','support','realizable'];rng.shuffle(routes)
        for route in routes:
            row=dict(c,route=route,repetition=rep,timeout=a.timeout,host=os.uname().nodename,jar_sha256=hashlib.sha256(a.jar.read_bytes()).hexdigest())
            start=time.monotonic()
            with tempfile.TemporaryDirectory(prefix='circuit-study-') as tmp:
                aig=str(Path(tmp)/'model.aig')
                if route=='dfa':args=['java','-Xmx4g','-jar',jar,str(path),'--one-variable','--run-native','--native-max-states','50000000','--timing']
                else:args=['java','-Xmx4g','-cp',jar,'brasp.CircuitStudy',str(path),route,aig]
                code,elapsed,raw=command(args,a.timeout);row['compile_wall_seconds']=elapsed
                if code==0 and route!='dfa':
                    code,solve,solver=command([abc,'-c',f'read_aiger {aig}; print_stats; scleanup; dc2; print_stats; pdr; print_status'],a.timeout-(time.monotonic()-start))
                    row['solver_wall_seconds']=solve;raw+='\n'+solver
                if code==-999:status='timeout'
                elif code!=0:status='size_limit' if any(s in raw.lower() for s in ('exceed','too large','case split')) else 'error'
                elif 'Property proved' in raw:status='empty'
                elif 'was asserted' in raw or 'counter-example' in raw:status='nonempty'
                elif 'UNKNOWN' in raw:status='unknown'
                elif 'NOT PROVED' in raw:status='nonempty'
                elif 'PROVED' in raw:status='empty'
                else:status='unknown'
                row.update(status=status,wall_seconds=time.monotonic()-start,command=args)
                if status in ('empty','nonempty'):row['matches_expected']=status==c['expected']
                row['structural_metrics']={k:v for k,v in re.findall(r'(states|goto|max_support|full_cells|support_cells|realizable_cells|compile_seconds|encode_seconds)=([^\s]+)',raw)}
                (logs/f'{a.cell}_{rep}_{route}.txt').write_text(raw)
            rows.append(row);(dest/f'{a.cell}.json').write_text(json.dumps(rows,indent=2)+'\n');print(a.cell,rep,route,status,flush=True)

def main():
    p=argparse.ArgumentParser();p.add_argument('command',choices=['plan','run']);p.add_argument('--out',type=Path,required=True);p.add_argument('--cell',type=int,default=0);p.add_argument('--jar',type=Path);p.add_argument('--abc',type=Path);p.add_argument('--timeout',type=int,default=120)
    a=p.parse_args();plan(a.out) if a.command=='plan' else run(a)
if __name__=='__main__':main()
