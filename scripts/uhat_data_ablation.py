#!/usr/bin/env python3
"""Paired data-size/sampler/hard-gradient ablation on the eight unresolved languages."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import random
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from uhat import brasp
from uhat.model import Schedule, UhatConfig
from uhat.tasks import datasets, enumerate_words, resolve
from uhat.programs import describe, program_task

NAMES = ['dot__k-8', 'marks__k-8', 'mono_word__sigma-4', 'no2a__k-4',
         'no2a__k-8', 'same__k-8', 'y__k-4', 'y__k-8']


def digest(value):
    return hashlib.sha256(json.dumps(value, separators=(',', ':')).encode()).hexdigest()


def unique_pool(task, count, limits, seed, excluded=()):
    """Mix uniform and existing draws, balance labels, interleave three length bands.

    Exhausted short bands contribute fewer words; no duplicates or silent shortfall.
    This is deliberately a diagnostic distribution, not uniform language sampling.
    """
    rng = random.Random(seed)
    lo, hi = limits
    edges = [lo + (hi - lo + 1) * i // 3 for i in range(4)]
    buckets = {(label, band): [] for label in (False, True) for band in range(3)}
    seen = set(map(tuple, excluded))
    half = count // 2
    for band in range(3):
        lower, upper = edges[band], edges[band + 1] - 1
        stale = 0
        for _ in range(1200):
            draws = [tuple(rng.choice(task.alphabet) for _ in range(rng.randint(lower, upper)))
                     for _ in range(64)]
            draws += task.sampler(task.alphabet, 64, lower, upper, rng)
            if task.name.startswith('figure2_no2a__k-'):
                distance = int(task.name.rsplit('-', 1)[1])
                for _ in range(64):
                    word = [rng.choice(task.alphabet) for _ in range(rng.randint(lower, upper))]
                    for position in range(distance, len(word) - 1):
                        if word[position] == word[position - distance] == 'a':
                            word[position] = 'b'
                    draws.append(tuple(word))
            added = 0
            for raw in draws:
                word = tuple(raw)
                label = task.label(word)
                bucket = buckets[label, band]
                if lower <= len(word) <= upper and word not in seen and len(bucket) < half:
                    seen.add(word)
                    bucket.append(word)
                    added += 1
            stale = 0 if added else stale + 1
            if all(len(buckets[label, band]) == half for label in (False, True)) or stale >= 30:
                break
    for bucket in buckets.values():
        rng.shuffle(bucket)
    per_label = {}
    for label in (False, True):
        queues = [iter(buckets[label, band]) for band in range(3)]
        words = []
        while len(words) < half:
            before = len(words)
            for queue in queues:
                word = next(queue, None)
                if word is not None and len(words) < half:
                    words.append(word)
            if before == len(words):
                raise ValueError(f'{task.name}: only {len(words)} unique examples for label {label}')
        per_label[label] = words
    # Every even prefix is label-balanced, allowing nested 512/2048/8192 subsets.
    result = [w for pair in zip(per_label[False], per_label[True]) for w in pair]
    assert len(set(result)) == count
    return result


def prepare(root, specs):
    (root / 'data').mkdir(parents=True, exist_ok=True)
    (root / 'specs').mkdir(exist_ok=True)
    (root / 'runs/logs').mkdir(parents=True, exist_ok=True)
    jobs = []
    for short in NAMES:
        name = 'figure2_' + short
        task = resolve(name)
        source = specs / (name + '.brasp')
        target = root / 'specs' / source.name
        target.write_text(source.read_text())
        facts = describe(source)
        conditions = ['original512', 'unique512', 'unique2048', 'unique8192']
        paths = [root / 'data' / f'{name}__{condition}.json' for condition in conditions]
        cached = all(p.exists() for p in paths)
        if cached and name.startswith('figure2_no2a'):
            cached = all(json.loads(p.read_text()).get('generator_version') == 2 for p in paths)
        if cached:
            for condition, data in zip(conditions, paths):
                payload = json.loads(data.read_text())
                assert payload['spec_sha256'] == hashlib.sha256(source.read_bytes()).hexdigest()
                for mode in ['legacy', 'straight_through']:
                    for seed in range(3):
                        jobs.append(dict(data=str(data), name=name, condition=condition, hard_loss=mode,
                                         seed=seed, layers=facts['depth'] + 1, heads=2, terms=2,
                                         steps=1500, batch=64))
            print(f'{name}: reused prepared datasets', flush=True)
            continue
        original_train, test = datasets(task, seed=0)
        exhaustive = enumerate_words(task.alphabet, task.lengths[0])
        pool = unique_pool(task, 8192, task.lengths[1], 20260922, exhaustive)
        diagnostic = unique_pool(task, 2000, task.lengths[2], 20260923, test)
        program = brasp.parse(source.read_text())
        # Validate actual new words, not merely the generator implementation.
        for word in pool + diagnostic:
            if brasp.accepts(program, word) != task.label(word):
                raise AssertionError((name, word))
        facts = describe(source)
        for condition in ['original512', 'unique512', 'unique2048', 'unique8192']:
            train = original_train if condition == 'original512' else exhaustive + pool[:int(condition[6:])]
            payload = dict(name=name, condition=condition, generator_version=2, train=train, test=test,
                           diagnostic=diagnostic, train_sha256=digest(train),
                           test_sha256=digest(test), diagnostic_sha256=digest(diagnostic),
                           train_unique=len(set(map(tuple, train))),
                           train_positive=sum(task.label(w) for w in train),
                           train_length_counts={str(n):sum(len(w)==n for w in train)
                                                for n in sorted(set(map(len, train)))},
                           spec=str(target), spec_sha256=hashlib.sha256(source.read_bytes()).hexdigest())
            data = root / 'data' / f'{name}__{condition}.json'
            data.write_text(json.dumps(payload) + '\n')
            for mode in ['legacy', 'straight_through']:
                for seed in range(3):
                    jobs.append(dict(data=str(data), name=name, condition=condition, hard_loss=mode,
                                     seed=seed, layers=facts['depth'] + 1, heads=2, terms=2,
                                     steps=1500, batch=64))
        print(f'{name}: prepared nested datasets; frozen test {digest(test)[:12]}', flush=True)
    (root / 'jobs.json').write_text(json.dumps(jobs, indent=2) + '\n')
    (root / 'README.md').write_text('''# Training-data and hard-gradient ablation

Eight unresolved languages; one fixed architecture per language (specification
depth + 1, two heads, two terms). Four data conditions: original sampling with
512 draws, and nested unique mixtures of 512, 2048, 8192 words plus the same
exhaustive short-word set. The mixture combines uniform and original generators,
plus a constructor allowing varied valid positive patterns for no2a,
balances sampled labels, and interleaves three length bands; exhausted short
bands contribute fewer examples. Per-length counts and exact datasets are saved.

Each condition has legacy and corrected hard-loss gradients, with paired seeds
0, 1, 2, 1500 steps and batch 64 on GPUs. All seeds run independently; there is
no test-based selection or early stopping. This fixes the number of optimizer
updates, not the number of epochs; larger datasets receive fewer passes.

The original 2000 longer-word evaluations are frozen from dataset seed 0.
A second fixed 2000-word unique diagnostic set broadens the sampling patterns.
Neither test set overlaps training lengths or is used to select a model.
Reported accuracies are finite-sample checks, not equivalence certificates.
The original512 control here uses batch 64, so it is not an exact replay of
the earlier full-batch sweep. Compare conditions within this experiment.
''')
    print(f'{len(jobs)} jobs prepared', flush=True)


def run_job(root, index, device, steps_override=None):
    import torch
    from uhat.train import train_once, batched_accepts
    from uhat.extract import extract
    job = json.loads((root / 'jobs.json').read_text())[index - 1]
    data = json.loads(Path(job['data']).read_text())
    for split in ['train', 'test', 'diagnostic']:
        if digest(data[split]) != data[split + '_sha256']:
            raise ValueError(f'{split} dataset changed after preparation')
    if hashlib.sha256(Path(data['spec']).read_bytes()).hexdigest() != data['spec_sha256']:
        raise ValueError('specification changed after preparation')
    task = program_task(data['spec'])
    config = UhatConfig(task.alphabet, job['layers'], job['heads'], job['terms'])
    schedule = Schedule(steps=steps_override or job['steps'], batch=job['batch'],
                        hard_loss=job['hard_loss'], seed=job['seed'])
    print(json.dumps(dict(event='training_started', index=index, **job,
                          train_words=len(data['train']), device=device)), flush=True)
    start = time.monotonic()
    fit = train_once(task, config, schedule, data['train'], job['seed'], False, device)
    program = extract(fit.model)
    result = dict(job, steps=schedule.steps, device=device, torch_version=torch.__version__,
                  seconds_training=time.monotonic()-start,
                  train_sha256=data['train_sha256'], test_sha256=data['test_sha256'],
                  diagnostic_sha256=data['diagnostic_sha256'], train_unique=data['train_unique'])
    for split in ['train', 'test', 'diagnostic']:
        words = data[split]
        answers = [brasp.accepts(program, w) for w in words]
        labels = [task.label(w) for w in words]
        model_answers = batched_accepts(fit.model, words, batch=16)
        result[split + '_accuracy'] = sum(a == b for a, b in zip(answers, labels)) / len(words)
        result[split + '_disagreements'] = sum(a != b for a, b in zip(answers, model_answers))
        result[split + '_words'] = len(words)
    if not math.isfinite(fit.loss):
        raise RuntimeError('nonfinite final loss')
    if not all(torch.isfinite(p).all() for p in fit.model.parameters()):
        raise RuntimeError('nonfinite trained parameters')
    result['loss'] = fit.loss
    result['seconds_total'] = time.monotonic() - start
    stem = root / 'runs' / f'{index:03d}'
    torch.save(fit.model.state_dict(), stem.with_suffix('.pt'))
    stem.with_suffix('.brasp').write_text(brasp.render(program))
    stem.with_suffix('.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)
    if any(result[s + '_disagreements'] for s in ['train', 'test', 'diagnostic']):
        raise RuntimeError('model/program disagreement')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'run', 'collect'])
    parser.add_argument('--root', type=Path, default=Path('results/uhat_data_ablation_20260921'))
    parser.add_argument('--specs', type=Path, default=Path('results/uhat_figure2_rerun_20260921/specs'))
    parser.add_argument('--index', type=int)
    parser.add_argument('--device', default='cuda')
    parser.add_argument('--steps', type=int)
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare(args.root, args.specs)
    elif args.command == 'run':
        run_job(args.root, args.index, args.device, args.steps)
    else:
        rows = [json.loads(p.read_text()) for p in sorted((args.root / 'runs').glob('*.json'))]
        if rows:
            with (args.root / 'summary.csv').open('w') as f:
                writer = csv.DictWriter(f, fieldnames=list(rows[0]))
                writer.writeheader()
                writer.writerows(rows)
        print(f'{len(rows)} completed results')


if __name__ == '__main__':
    main()
