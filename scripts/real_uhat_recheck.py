#!/usr/bin/env python3
"""Re-extract and re-certify frozen checkpoints without changing the original study."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys
import time

from real_uhat_study import ROOT, balanced_bands, digest, predicate, predictions, write
from real_uhat_certify import check


def main():
    import torch
    from uhat import brasp
    from uhat.real_extract import build_program, class_tables
    from uhat.real_model import RealUhat, RealUhatConfig

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--legacy-model', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--abc', type=Path, required=True)
    parser.add_argument('--index', type=int, default=0)
    parser.add_argument('--shards', type=int, default=1)
    args = parser.parse_args()
    torch.set_num_threads(2)
    torch.use_deterministic_algorithms(True)
    spec = importlib.util.spec_from_file_location('legacy_real_model', args.legacy_model)
    legacy = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = legacy
    spec.loader.exec_module(legacy)
    data = json.loads((args.original / 'dataset.json').read_text())
    original_sets = {'train': data['train'], 'validation': data['validation'], **data['bands']}
    manifest = [json.loads(line) for line in (args.original / 'manifest.jsonl').read_text().splitlines()]
    source_paths = [Path(__file__), ROOT/'uhat/real_model.py', ROOT/'uhat/real_extract.py',
                    ROOT/'scripts/real_uhat_study.py', args.legacy_model]
    sources = {str(path): digest(path.read_bytes()) for path in source_paths}
    for index, cell in enumerate(manifest):
        if index % args.shards != args.index:
            continue
        started = time.monotonic()
        checkpoint = args.original/'checkpoints'/f'{cell["id"]}.pt'
        saved = torch.load(checkpoint, map_location='cpu', weights_only=False)
        config = dict(alphabet=('a', 'b'), width=cell['width'], layers=cell['layers'], heads=cell['heads'])
        model = RealUhat(RealUhatConfig(**config))
        old = legacy.RealUhat(legacy.RealUhatConfig(**config))
        for m in (model, old):
            m.load_state_dict(saved['state_dict'])
            m.eval()
        tables = class_tables(model, cap=cell['cap'])
        program = brasp.render(build_program(model, cap=cell['cap']))
        dest = args.out/'programs'/f'{cell["id"]}.brasp'
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(program)
        archived = json.loads((args.original/'records'/f'{cell["id"]}.json').read_text())
        certificate = json.loads((args.original/'certificates'/f'{cell["id"]}.json').read_text())
        row = dict(cell, checkpoint_sha256=digest(checkpoint.read_bytes()),
                   original_checkpoint_hash_matches=digest(checkpoint.read_bytes()) == archived['checkpoint_sha256'],
                   source_sha256=sources, torch=torch.__version__, classes=[len(t.values) for t in tables],
                   program_sha256=digest(program.encode()),
                   program_unchanged=digest(program.encode()) == archived['program_sha256'],
                   original_certificate=certificate['status'], evaluations={})
        for name, words in {**original_sets, **balanced_bands(cell['task'])}.items():
            labels = [predicate(cell['task'], w) for w in words]
            normal = predictions(model, words)
            snapped = predictions(model, words, [t.values for t in tables])
            previous = predictions(old, words)
            row['evaluations'][name] = dict(n=len(words), unique_words=len(set(map(tuple, words))),
                positive=sum(labels), old_new_disagreements=sum(a != b for a,b in zip(previous,normal)),
                ordinary_snapped_disagreements=sum(a != b for a,b in zip(normal,snapped)),
                ordinary_correct=sum(a == b for a,b in zip(normal,labels)),
                old_correct=sum(a == b for a,b in zip(previous,labels)),
                # Store vectors so the frozen recheck itself is independently auditable.
                ordinary_predictions=normal, snapped_predictions=snapped, old_predictions=previous)
        command = ['java', '-Xmx4g', '-jar', str(args.jar.resolve()), '--equivalent',
                   str((args.original/'specs'/f'{cell["task"]}.brasp').resolve()), str(dest.resolve()),
                   '--run-abc', '--abc-bin', str(args.abc.resolve()), '--abc-raw', '--timing']
        status, elapsed, raw = check(command, 120)
        (dest.with_suffix('.log')).write_text(raw)
        row.update(certificate=status, certificate_seconds=elapsed, seconds=time.monotonic()-started,
                   jar_sha256=digest(args.jar.read_bytes()), abc_sha256=digest(args.abc.read_bytes()))
        write(args.out/'records'/f'{cell["id"]}.json', row)
        print(cell['id'], status, 'unchanged' if row['program_unchanged'] else 'changed', flush=True)


if __name__ == '__main__':
    main()
