import collections,hashlib,json,statistics
from pathlib import Path
root=Path(__file__).resolve().parent
base=root/'real_uhat_systematic_20260911'
manifest=[json.loads(s) for s in (base/'manifest.jsonl').read_text().splitlines()]
rows=[]
for c in manifest:
    r=json.loads((base/'records'/f'{c["id"]}.json').read_text())
    cert=json.loads((base/'certificates'/f'{c["id"]}.json').read_text())
    raw=(base/'certificates'/f'{c["id"]}.log').read_text()
    assert r['status']=='evaluated'
    assert hashlib.sha256((base/'programs'/f'{c["id"]}.brasp').read_bytes()).hexdigest()==cert['program_sha256']==r['program_sha256']
    assert hashlib.sha256((base/'specs'/f'{c["task"]}.brasp').read_bytes()).hexdigest()==c['spec_sha256']
    assert (cert['status']=='equivalent' and 'Property proved' in raw) or (cert['status']=='inequivalent' and ('was asserted' in raw or 'counter-example' in raw))
    r['certificate']=cert;rows.append(r)
circuit=[r for p in (root/'circuit_study_20260911/records').glob('*.json') for r in json.loads(p.read_text())]
assert len(circuit)==288
keys=[(r['family'],r['parameter'],r['route'],r['repetition']) for r in circuit]
assert len(set(keys))==288
assert not any(r.get('matches_expected') is False for r in circuit)
lines=['# Audited systematic-study results','', 'All MPI jobs have finished. This audit joins 300 manifest cells with their records and certificates, verifies program/specification hashes, and checks raw ABC verdicts. All 300 completed extraction and all six evaluation bands; no missing cells or ambiguous equivalence verdicts were found. Checkpoints remain on MPI; records, programs, and raw solver logs are copied here.','', '## Real-UHAT pilot','', '| Task | Certified equivalent | Attempted |','|---|---:|---:|']
pilot=[r for r in rows if r['study']=='pilot']
for task in sorted({r['task'] for r in pilot}):
    rs=[r for r in pilot if r['task']==task]
    lines.append(f'| {task} | {sum(r["certificate"]["status"]=="equivalent" for r in rs)} | {len(rs)} |')
lines+=['','Overall: **138/180 (76.7%)** pilot programs equivalent; 42 inequivalent. These certify extracted programs against their specifications on nonempty words. They do not establish universal equality between those programs and unsnapped numerical models.','', '## Gate ablation','', 'Each entry is certified-equivalent seeds out of ten, at width 6, one layer, one head. Seeds and datasets are paired across conditions.','', '| Task | Fixed one | Learned | Learned + penalty | Fixed zero |','|---|---:|---:|---:|---:|']
for task in ['ends_ab','repeat_final','since_a']:
    vals=[]
    for gate in ['fixed_one','learned','learned_penalty','fixed_zero']:
        rs=[r for r in rows if r['study']=='gate' and r['task']==task and r['gate']==gate]
        vals.append(str(sum(r['certificate']['status']=='equivalent' for r in rs))+'/10')
    lines.append('| '+task+' | '+' | '.join(vals)+' |')
lines+=['', 'The strongest controlled finding is ends-ab: the penalty condition succeeds in all ten seeds versus zero with fixed-one scores. Every successful ends-ab gate-ablation model has an exactly inactive score gate. Fixed-zero fails on repeat-final and since-a, so positional attention is not a general replacement for content-dependent attention. Ten seeds support this setup-specific conclusion, not a universal necessity claim.','', '## Extraction fidelity and test coverage','', 'There were **zero observed disagreements** between ordinary hardened, snapped, and extracted predictions in all 459,600 model–word evaluations (training/validation and the exhaustive audit overlap). Each comparison is finite; this is implementation evidence, not an all-input extraction proof.','', '| Study | Perfect on all long bands | Inequivalent among these | Perfect training + validation | Inequivalent among these |','|---|---:|---:|---:|---:|']
for study in ['pilot','gate']:
    rs=[r for r in rows if r['study']==study]
    long=[r for r in rs if all(r['ordinary_accuracy'][k]==1 for k in ('test_9_16','test_17_32','test_33_64'))]
    val=[r for r in rs if all(r['ordinary_accuracy'][k]==1 for k in ('train','validation'))]
    lines.append(f'| {study} | {len(long)} | {sum(r["certificate"]["status"]=="inequivalent" for r in long)} | {len(val)} | {sum(r["certificate"]["status"]=="inequivalent" for r in val)} |')
lines+=['', '**Test-distribution limitation:** all 768 long samples for all-a are negative, while all 768 long samples for repeat-final and subsequence-ab are positive. Report this label balance, and add balanced/adversarial test sets before claiming robust length generalization. Thirty-two pilot models pass long tests but are wrong; most already fail training. One pilot model passes training and all long tests but fails validation. No model passing both training and validation was found inequivalent.','', 'Independent short-word checks confirm examples in `audited_witnesses.json`:','']
for w in json.loads((root/'audited_witnesses.json').read_text()):
    lines.append(f'- `{w["id"]}`: shortest witness `{w["word"]}`; specification accepts={w["spec_accepts"]}, program accepts={w["program_accepts"]}.')
lines+=['','## Circuit ablation','', '| Route | Instances solved in all three repetitions | Size-limited instances | Timed-out instances |','|---|---:|---:|---:|']
for route in ['dfa','full','support','realizable']:
    rs=[r for r in circuit if r['route']==route]
    counts=collections.Counter(r['status'] for r in rs)
    lines.append(f'| {route} | {(counts["empty"]+counts["nonempty"])//3}/24 | {counts["size_limit"]//3} | {counts["timeout"]//3} |')
lines+=['', 'All 225 conclusive measurements agree with the declared benchmark semantics, with no conflicting repetitions. The other 63 measurements are 57 size-limit refusals and six timeouts, not solver misclassifications.','', '- At lookback 32, Y takes a median 0.59 s and no2a 0.62 s with realizability reduction; the DFA route hits the 120 s deadline on all repetitions of both instances.', '- At alphabet size 8, Since takes median 35.41 s via DFA versus 0.59 s via realizability reduction; same-letter-before takes 28.00 s versus 0.56 s.', '- At alphabet size 32, same-letter-before has 8,589,934,656 theoretical support-table cells, reduced to 130 retained cells/latches; median runtime is 0.70 s. The unreduced support route is refused by the explicit work cap.', '', 'These are randomized-order, three-repetition measurements on allocated MPI CPUs, not a claim of an otherwise idle physical host. The 500,000 cell-symbol-work cap is an implementation guard. Do not present cap refusals as measured exponential runtimes. The mono family remains a BOS-induced empty control, not a difficult semantic proof workload.','', '## Limits and next use in the paper','', '- Insert the gate table and full pilot denominator; they support stronger claims than the original selected-success anecdote.', '- Add solved counts and structural sizes to the circuit comparison. Keep the new MPI timings separate from the historical laptop table.', '- Treat long-word accuracy cautiously; add balanced short/long boundary cases before using it as the main generalization metric.', '- All sampled extraction sizes are (12), (48), or (12,156). No run challenges the 20,000-tuple cap, so this pilot does not measure the deeper extraction failure boundary.', '- The circuit pilot covers emptiness only; equivalent/mutated-program scalability queries and an ABC preprocessing ablation remain future extensions.', '', 'Training medians should use `train_seconds`, not total Slurm elapsed time. Across the 300 cells training spans %.2f–%.2f s (median %.2f s); equivalence checking spans %.2f–%.2f s (median %.2f s). Long evaluation jobs should not be reported as slow training.'%(min(r['train_seconds'] for r in rows),max(r['train_seconds'] for r in rows),statistics.median(r['train_seconds'] for r in rows),min(r['certificate']['seconds'] for r in rows),max(r['certificate']['seconds'] for r in rows),statistics.median(r['certificate']['seconds'] for r in rows))]
(root/'AUDIT.md').write_text('\n'.join(lines)+'\n')
print(root/'AUDIT.md')
