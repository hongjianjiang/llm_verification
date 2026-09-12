#!/usr/bin/env python3
"""Produce descriptive counts from finished MPI study artifacts; no inferred verdicts."""
from collections import Counter,defaultdict
import json
from pathlib import Path

root=Path(__file__).resolve().parents[1]
train=root/'results/real_uhat_systematic_20260911'
circuit=root/'results/circuit_study_20260911'
manifest=[json.loads(s) for s in (train/'manifest.jsonl').read_text().splitlines()]
lines=['# Systematic experiment results','', 'Automatically collected descriptive results. Equivalence verdicts concern extracted programs on nonempty words, not unsnapped numerical transformers. Missing, interrupted, timed-out, and unsupported runs remain in denominators.','', '| Study | Planned | Recorded | Evaluated | Equivalent | Inequivalent | Other/missing certificate |','|---|---:|---:|---:|---:|---:|---:|']
all_records=[]
summaries=[]
for study in ('pilot','gate'):
    cells=[c for c in manifest if c['study']==study]; records=[];certs=[]
    for c in cells:
        path=train/'records'/f'{c["id"]}.json'
        if path.exists():records.append(json.loads(path.read_text()))
        path=train/'certificates'/f'{c["id"]}.json'
        certs.append(json.loads(path.read_text()).get('status','missing') if path.exists() else 'missing')
    all_records+=records
    counts=Counter(certs)
    lines.append(f'| {study} | {len(cells)} | {len(records)} | {sum(r["status"]=="evaluated" for r in records)} | {counts["equivalent"]} | {counts["inequivalent"]} | {len(cells)-counts["equivalent"]-counts["inequivalent"]} |')
    summaries+=['',f'{study} stage outcomes: `{dict(Counter(r["status"] for r in records))}`. Certificate outcomes: `{dict(counts)}`.','']
lines+=summaries
lines+=['## Extraction checks','','Counts below include only records with a completed band evaluation. Zero observed disagreements is a finite-sample check.','', '| Band | Models measured | Ordinary/snapped disagreements | Snapped/program disagreements |','|---|---:|---:|---:|']
for band in ('train','validation','audit_1_8','test_9_16','test_17_32','test_33_64'):
    rs=[r['evaluations'][band] for r in all_records if band in r.get('evaluations',{})]
    lines.append(f'| {band} | {len(rs)} | {sum(r["ordinary_snapped_disagreements"] for r in rs)} | {sum(r["snapped_program_disagreements"] for r in rs)} |')
rows=[r for p in (circuit/'records').glob('*.json') for r in json.loads(p.read_text())]
lines+=['','## Circuit comparison','',f'{len(rows)} of 288 planned measurements recorded. Counts: `{dict(Counter(r["status"] for r in rows))}`.','']
groups=defaultdict(set)
for r in rows:
    if r['status'] in ('empty','nonempty'):groups[(r['family'],r['parameter'])].add(r['status'])
inconsistent=[str(k) for k,v in groups.items() if len(v)>1]
wrong=[f'{r["family"]}/{r["parameter"]}/{r["route"]}/{r["repetition"]}' for r in rows if r.get('matches_expected') is False]
lines += [f'Conflicting semantic verdicts: `{inconsistent}`.',f'Disagreements with benchmark expectations: `{wrong}`.','', 'Raw timings and structural metrics are retained per repetition. Timeouts are censored; no timeout-substituted medians or speedups are reported. Investigate any semantic inconsistency before interpreting performance.']
(root/'REPORT.md').write_text('\n'.join(lines)+'\n')
print('\n'.join(lines))
