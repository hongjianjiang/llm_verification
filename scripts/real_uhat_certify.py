#!/usr/bin/env python3
"""Certify every extracted study program; preserve failures and raw solver logs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def check(command,timeout):
    started=time.monotonic()
    proc=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,start_new_session=True)
    try:
        text,_=proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid,signal.SIGKILL);text,_=proc.communicate()
        return 'timeout',time.monotonic()-started,text
    if proc.returncode:status='error'
    elif 'Property proved' in text:status='equivalent'
    elif 'was asserted' in text or 'counter-example' in text:status='inequivalent'
    elif 'unsupported' in text.lower():status='unsupported'
    else:status='unknown'
    return status,time.monotonic()-started,text


def main():
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--jar',type=Path,required=True);p.add_argument('--abc',type=Path,required=True);p.add_argument('--timeout',type=int,default=120);p.add_argument('--index',type=int,default=0);p.add_argument('--shards',type=int,default=1)
    a=p.parse_args();dest=a.out/'certificates';dest.mkdir(exist_ok=True)
    manifest=[json.loads(s) for s in (a.out/'manifest.jsonl').read_text().splitlines()]
    for i,c in enumerate(manifest):
        if i%a.shards!=a.index:continue
        program=a.out/'programs'/f'{c["id"]}.brasp';target=dest/f'{c["id"]}.json'
        spec=a.out/'specs'/f'{c["task"]}.brasp'
        row={'id':c['id'],'task':c['task'],'study':c['study'],'status':'no_extracted_program'}
        if program.exists():
            command=['java','-Xmx4g','-jar',str(a.jar.resolve()),'--equivalent',str(spec.resolve()),str(program.resolve()),'--run-abc','--abc-bin',str(a.abc.resolve()),'--abc-raw','--timing']
            status,elapsed,raw=check(command,a.timeout)
            row.update(status=status,seconds=elapsed,timeout=a.timeout,program_sha256=hashlib.sha256(program.read_bytes()).hexdigest(),jar_sha256=hashlib.sha256(a.jar.read_bytes()).hexdigest(),abc_sha256=hashlib.sha256(a.abc.read_bytes()).hexdigest(),command=command,scope='Extracted program versus specification on nonempty words; not an unsnapped-model certificate.')
            (dest/f'{c["id"]}.log').write_text(raw)
        target.write_text(json.dumps(row,indent=2)+'\n');print(c['id'],row['status'],flush=True)
if __name__=='__main__':main()
