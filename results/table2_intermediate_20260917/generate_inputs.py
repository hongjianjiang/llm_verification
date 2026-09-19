import sys
from pathlib import Path
sys.path.insert(0, "/Users/alexander/work/llm_verification")
from ltl2_generator.ast import *
from ltl2_generator.print import brasp_ltl
from ltl2_generator.families.core import _same_mask, _b_or

def y(k):
    f = Letter("a")
    for _ in range(k): f = Yst(AtJ(f))
    return f, ("a", "b"), f"y_depth__k-{k}"

def no2a(k):
    close = Letter("a")
    for _ in range(k): close = Yst(AtJ(close))
    return Hist(AtJ(not1(and1(Letter("a"), close)))), ("a", "b"), f"y_depth__no_two_a__k-{k}"

def alpha(n):
    return tuple(chr(ord("a") + i) for i in range(n))

def slb(n):
    a = alpha(n); return Once(_same_mask(a)), a, f"two_var__same_letter_before__sigma-{n}"

def mono(n):
    a = alpha(n)
    return Hist(_b_or([andb(AtI(Letter(a[t])), AtJ(Letter(a[s]))) for t in range(n) for s in range(t)])), a, f"two_var__monotone_past__sigma-{n}"

def since(n):
    a = alpha(n)
    return Since(_same_mask(a), AtJ(Letter("marker"))), ("marker",) + a, f"two_var__since_same_letter__sigma-{n}"

FAM = dict(Y=y, no2a=no2a, slb=slb, mono=mono, since=since)
if __name__ == "__main__":
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    for spec in sys.argv[2:]:
        fam, p = spec.split(":")
        formula, alphabet, name = FAM[fam](int(p))
        (out / f"{name}.ltl").write_text(brasp_ltl(formula, list(alphabet)))
        print(out / f"{name}.ltl")
