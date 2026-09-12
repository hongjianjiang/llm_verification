"""Audit the frozen-checkpoint recheck and regenerate its report."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parent
ORIGINAL = ROOT.parent/'mpi_real_uhat_20260911'/'real_uhat_systematic_20260911'
rows = sorted((json.loads(p.read_text()) for p in (ROOT/'records').glob('*.json')), key=lambda r:r['id'])
manifest = [json.loads(line) for line in (ORIGINAL/'manifest.jsonl').read_text().splitlines()]
assert {r['id'] for r in rows} == {r['id'] for r in manifest}
assert len(rows) == 300
sources = rows[0]['source_sha256']
assert all(r['source_sha256'] == sources for r in rows)
legacy_key = '/home/hongjian/llm_verification/experiments/real_uhat_20260911/uhat/real_model.py'
assert hashlib.sha256((ROOT/'legacy_real_model.py').read_bytes()).hexdigest() == sources[legacy_key]
with tarfile.open(ROOT/'source.tar.gz') as archive:
    for path, expected in sources.items():
        if path != legacy_key:
            relative = path.split('/real_uhat_fix_20260911/')[1]
            assert hashlib.sha256(archive.extractfile(relative).read()).hexdigest() == expected

for row in rows:
    old = json.loads((ORIGINAL/'records'/f'{row["id"]}.json').read_text())
    old_cert = json.loads((ORIGINAL/'certificates'/f'{row["id"]}.json').read_text())
    program = ROOT/'programs'/f'{row["id"]}.brasp'
    assert hashlib.sha256(program.read_bytes()).hexdigest() == row['program_sha256'] == old['program_sha256'] == old_cert['program_sha256']
    assert row['checkpoint_sha256'] == old['checkpoint_sha256']
    assert hashlib.sha256((ROOT/'checkpoints'/f'{row["id"]}.pt').read_bytes()).hexdigest() == row['checkpoint_sha256']
    assert row['original_checkpoint_hash_matches'] and row['program_unchanged']
    assert row['certificate'] == row['original_certificate'] == old_cert['status']
    raw = program.with_suffix('.log').read_text()
    if row['certificate'] == 'equivalent':
        assert 'Property proved' in raw and 'was asserted' not in raw
    else:
        assert row['certificate'] == 'inequivalent' and 'was asserted' in raw and 'Property proved' not in raw
    for name, evaluation in row['evaluations'].items():
        assert len(evaluation['ordinary_predictions']) == len(evaluation['snapped_predictions']) == len(evaluation['old_predictions']) == evaluation['n']
        assert evaluation['ordinary_predictions'] == evaluation['snapped_predictions'] == evaluation['old_predictions']
        assert evaluation['old_new_disagreements'] == evaluation['ordinary_snapped_disagreements'] == 0
        if name.startswith('balanced'):
            assert evaluation['n'] == 256 and evaluation['positive'] == 128
        else:
            assert abs(evaluation['old_correct']/evaluation['n'] - old['ordinary_accuracy'][name]) < 1e-12

def perfect(row, prefix):
    return all(e['ordinary_correct'] == e['n'] for name,e in row['evaluations'].items() if name.startswith(prefix))

lines = [
    '# Frozen-checkpoint numerical-fix audit',
    '',
    'MPI job array 50502127 completed all eight shards successfully (1:44–2:02 per shard). No training was repeated. The original study and its checkpoints were preserved; the exact patched source snapshot is in `source.tar.gz`.',
    '',
    'All 300 checkpoint hashes match the original records. All 300 re-extracted programs are byte-for-byte unchanged. Fresh raw ABC verdicts match all original certificates: **191 equivalent, 109 inequivalent** (pilot 138/180; gate 53/120). The original program certificates remain applicable.',
    '',
    'Ordinary predictions before/after the fixes and corrected snapped predictions agree in all **690,000** model–word evaluations: 459,600 original evaluations plus 230,400 balanced diagnostics. Recomputed original accuracies also match the archived records. The balanced diagnostics compare numerical models; programs were not re-interpreted on those new words. The archived program/model comparisons plus unchanged program hashes support the original finite fidelity checks, not an all-input numerical correspondence theorem.',
    '',
    '## Balanced long-word diagnostics',
    '',
    'Each task uses 128 positive and 128 negative samples in each length band, shared across model seeds. Sampling is deterministic (20260913 + task index) and with replacement. Rare classes are explicitly constructed: all-a positives; repeated-final negatives with a constant opposite-symbol prefix; subsequence-ab negatives of the form b* a*; and all-b negatives for since-a mixed with words ending in a. Other conditions use constrained suffixes or rejection sampling. These are targeted diagnostic distributions, not uniform-word generalization estimates. Repeated words are retained; unique-word counts are saved per record.',
    '',
    '| Study | Perfect uniform long bands | Perfect balanced long bands | Inequivalent among balanced-perfect |',
    '|---|---:|---:|---:|',
]
for study in ('pilot', 'gate'):
    group = [r for r in rows if r['study'] == study]
    selected = [r for r in group if perfect(r, 'balanced_')]
    lines.append(f'| {study} | {sum(perfect(r,"test_") for r in group)}/{len(group)} | {len(selected)}/{len(group)} | {sum(r["certificate"] != "equivalent" for r in selected)} |')
    print(study, 'failures exposed beyond uniform', sum(perfect(r,'test_') and not perfect(r,'balanced_') for r in group))

lines += ['', 'Pilot mean numerical accuracy across all 30 runs per task:', '',
          '| Task | Length 9–16 | Length 17–32 | Length 33–64 |', '|---|---:|---:|---:|']
for task in sorted({r['task'] for r in rows if r['study'] == 'pilot'}):
    group = [r for r in rows if r['study'] == 'pilot' and r['task'] == task]
    values = [sum(r['evaluations'][name]['ordinary_correct'] for r in group)/(256*len(group)) for name in ('balanced_9_16','balanced_17_32','balanced_33_64')]
    lines.append('| '+task+' | '+' | '.join(f'{v:.2%}' for v in values)+' |')
lines += ['', 'Two inequivalent pilot programs pass every balanced long band:', '']
lines += ['- `'+r['id']+'`' for r in rows if r['study']=='pilot' and perfect(r,'balanced_') and r['certificate']=='inequivalent']
lines += ['', '## Interpretation', '',
    'The edge-case regressions establish real implementation defects (finite masking sentinel, rounded straight-through weights, approximate score ties and activation merging, and multihead residual accumulation order). They do not explain the failed training runs in this cohort: these fixes change neither the emitted programs nor any checked prediction. The measured success rates belong to archived training; a fresh training experiment with corrected gradients has not been run.', '',
    'Local verification: the existing UHAT/extraction suite and seven initial numerical regressions passed (23 tests, one optional Scala bridge test skipped). The final numerical/sampling suite has eight passing tests, including both-label coverage in all diagnostic bands. The manuscript was compiled and its edited tables visually inspected.', '',
    'Reproduce this audit with `python3 results/mpi_real_uhat_fix_20260911/audit_recheck.py`. It checks IDs, checkpoint/program hashes, fresh raw solver verdicts, prediction vectors, archived accuracy agreement, and diagnostic class counts.',
]
(ROOT/'AUDIT.md').write_text('\n'.join(lines)+'\n')
print('Audited',len(rows),'records:',dict(Counter(r['certificate'] for r in rows)))
