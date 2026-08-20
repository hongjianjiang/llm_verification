"""The M4 families: dot depth, Y depth, true coupling, and controls."""
from __future__ import annotations
from itertools import combinations
from ..ast import *
from ..benchmark import Benchmark
from ..dfa import dfa_for_recognizer


def _b_or(items: list[Binary]) -> Binary:
    return orb(*items)


def _same_mask(alphabet: tuple[str,...]) -> Binary:
    return _b_or([andb(AtI(Letter(s)), AtJ(Letter(s))) for s in alphabet])


def dot_depth() -> list[Benchmark]:
    result=[]
    for sigma_size in (2,4):
        alphabet=tuple(chr(ord("a")+i) for i in range(sigma_size))
        for k in range(1,9):
            letters=tuple(alphabet[i % len(alphabet)] for i in range(k))
            prefix: Unary = Letter(letters[0])
            for letter in letters[1:]:
                prefix = and1(Letter(letter), Once(AtJ(prefix)))
            formula = or1(prefix, Once(AtJ(prefix)))
            result.append(Benchmark(f"dot_depth/k={k}/sigma={sigma_size}", "dot_depth", {"k":k,"sigma_size":sigma_size}, alphabet, formula, True,
                f"contains {''.join(letters)!r} as a scattered subsequence", extra_metrics={"dot_depth_bound":k}))
    return result


def y_depth() -> list[Benchmark]:
    result=[]; alphabet=("a","b")
    for k in range(1,17):
        formula: Unary=Letter("a")
        for _ in range(k): formula=Yst(AtJ(formula))
        result.append(Benchmark(f"y_depth/k={k}", "y_depth", {"k":k,"kind":"kth_from_end"}, alphabet, formula, True,
            f"the {k}-th symbol before the end is a"))
    for k in range(1,9):
        # At the last position this rejects iff there is an a in the preceding
        # k positions; putting the same clause at every a via H makes it global.
        previous: Unary = Top()
        for _ in range(k): previous = Yst(AtJ(previous))
        # Direct formula: no position in the past is an a while current is a,
        # bounded by a chain of strict Y.  This explicit family stays small.
        close: Unary = Letter("a")
        for _ in range(k): close = Yst(AtJ(close))
        formula = Hist(AtJ(not1(and1(Letter("a"), close))))
        result.append(Benchmark(f"y_depth/no_two_a/k={k}", "y_depth", {"k":k,"kind":"no_two_a"}, alphabet, formula, True,
            f"a conservative local-window no-two-a instance at distance {k}"))
    return result


def two_var() -> list[Benchmark]:
    result=[]
    for n in (2,4,8,16,32):
        alphabet=tuple(chr(ord("a")+i) for i in range(n))
        same=_same_mask(alphabet)
        result += [
            Benchmark(f"two_var/same_letter_before/sigma={n}", "two_var", {"kind":"same_letter_before","sigma_size":n}, alphabet, Once(same), True, "some earlier position carries the current letter", extra_metrics={"compile_state_cap":500} if n >= 16 else {}),
            Benchmark(f"two_var/prev_repeats/sigma={n}", "two_var", {"kind":"prev_repeats","sigma_size":n}, alphabet, Yst(same), True, "the preceding letter equals the current letter", extra_metrics={"compile_state_cap":500} if n >= 16 else {}),
            Benchmark(f"two_var/monotone_past/sigma={n}", "two_var", {"kind":"monotone_past","sigma_size":n}, alphabet,
                Hist(_b_or([andb(AtI(Letter(alphabet[t])), AtJ(Letter(alphabet[s]))) for t in range(n) for s in range(t)])), True,
                "every earlier letter is strictly smaller than the current letter", extra_metrics={"compile_state_cap":500} if n >= 16 else {}),
        ]
        # `#` would be a comment delimiter in the repository's `.ltl` text
        # format, so use an ordinary named marker in exported artefacts.
        hash_alphabet=("marker",)+alphabet
        result.append(Benchmark(f"two_var/since_same_letter/sigma={n}", "two_var", {"kind":"since_same_letter","sigma_size":n}, hash_alphabet,
            Since(same, AtJ(Letter("marker"))), True, "current-letter runs back to an earlier marker", extra_metrics={"compile_state_cap":500} if n >= 16 else {}))
    return result


def negative_controls() -> list[Benchmark]:
    alphabet=("a","b")
    parity=lambda: dfa_for_recognizer(alphabet,2,0,{0},lambda q,s: q ^ int(s=="a"))
    mod3=lambda: dfa_for_recognizer(alphabet,3,0,{0},lambda q,s: (q+int(s=="a"))%3)
    # Dyck-1 is intentionally recognised only up to the bounded DFA sentinel;
    # its non-star-freeness is instead represented by parity/modulo controls.
    return [Benchmark("not_star_free/parity", "not_star_free", {"kind":"parity"}, alphabet, None, False, "even number of a symbols", "designed", parity),
            Benchmark("not_star_free/mod3", "not_star_free", {"kind":"mod3"}, alphabet, None, False, "number of a symbols is 0 modulo 3", "designed", mod3)]


def core_benchmarks() -> list[Benchmark]:
    return dot_depth()+y_depth()+two_var()+negative_controls()
