"""Deterministic corpus builder: `python -m ltl2.build --out corpus`."""
from __future__ import annotations
import argparse, csv, json, shutil
from pathlib import Path
from . import __version__
from .families import REGISTRY
from .print import brasp_ltl


def build(out: Path, seed: int = 20260818, cap: int = 200_000) -> list[dict]:
    if out.exists(): shutil.rmtree(out)
    (out / "dfa").mkdir(parents=True)
    (out / "ltl").mkdir(parents=True)
    all_benchmarks=[]
    for generator in REGISTRY.values(): all_benchmarks.extend(generator())
    records=[]
    for benchmark in sorted(all_benchmarks, key=lambda b:b.id):
        record, dfa=benchmark.record(cap)
        if record["metrics"]["aperiodic"] is not None and record["metrics"]["aperiodic"] != record["expected"]["star_free"]:
            raise AssertionError(f"aperiodicity mismatch for {benchmark.id}")
        records.append(record)
        if dfa:
            path=out / "dfa" / (benchmark.id.replace("/","__").replace("=","-") + ".json")
            path.write_text(json.dumps(dfa.to_json(), sort_keys=True, separators=(",",":")) + "\n")
        if benchmark.formula is not None:
            path=out / "ltl" / (benchmark.id.replace("/","__").replace("=","-") + ".ltl")
            path.write_text(brasp_ltl(benchmark.formula, list(benchmark.alphabet)))
    (out / "benchmarks.jsonl").write_text("".join(json.dumps(r,sort_keys=True,separators=(",",":")) + "\n" for r in records))
    fields=["id","family","size_ltl2","size_pltl_optimised","blowup_ratio","dfa_states","monoid_size","aperiodic","compilation_timeout"]
    with (out/"index.csv").open("w",newline="") as f:
        writer=csv.DictWriter(f,fieldnames=fields); writer.writeheader()
        for r in records: writer.writerow({"id":r["id"],"family":r["family"],**{x:r["metrics"].get(x) for x in fields[2:]}})
    counts={}
    for r in records: counts[r["family"]]=counts.get(r["family"],0)+1
    (out/"MANIFEST.md").write_text("# LTL₂ corpus manifest\n\n"+f"- library version: `{__version__}`\n- seed: `{seed}`\n- strict operators: `true`\n- empty word: excluded (`Σ⁺`)\n- state cap: `{cap}`\n\n"+"| family | count |\n| --- | ---: |\n"+"".join(f"| {k} | {v} |\n" for k,v in sorted(counts.items())))
    return records


def main() -> None:
    parser=argparse.ArgumentParser(); parser.add_argument("--out",type=Path,default=Path("corpus")); parser.add_argument("--seed",type=int,default=20260818); parser.add_argument("--state-cap",type=int,default=200000)
    args=parser.parse_args(); records=build(args.out,args.seed,args.state_cap)
    print(f"wrote {len(records)} benchmarks to {args.out}")

if __name__ == "__main__": main()
