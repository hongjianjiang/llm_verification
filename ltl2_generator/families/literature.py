"""Benchmark families taken from the temporal-logic literature.

Every family here is stated in its source over *atomic propositions*, while
this generator's alphabet is a set of opaque letters.  They are therefore
encoded with `Bit` atoms over an alphabet of bit-string letters: proposition
`p_k` becomes character `k` of the current symbol.  Spelling them with
`Letter` instead would cost `O(2^n)` syntax for an `O(n)` property and would
destroy the very succinctness these families exist to witness.
"""
from __future__ import annotations
from itertools import product
from ..ast import *
from ..benchmark import Benchmark


def _bit_alphabet(width: int) -> tuple[str, ...]:
    return tuple("".join(bits) for bits in product("01", repeat=width))


def _agree(index: int) -> Binary:
    """Positions `i` and `j` carry the same value of proposition `index`."""
    return orb(andb(AtI(Bit(index)), AtJ(Bit(index))),
               andb(AtI(not1(Bit(index))), AtJ(not1(Bit(index)))))


def markey_agreement() -> list[Benchmark]:
    """Any position agreeing with the anchor on p_1..p_n agrees on p_0.

    Markey's succinctness witness, credited there to Etessami, Vardi and
    Wilke and proved in Laroussinie, Markey and Schnoebelen (LICS'02): the
    property has an `O(n)` past formula while every pure-future formula
    needs size `2^Omega(n)`, and every automaton for it at least `2^(2^n)`
    states.  Markey anchors at position 0; the anchor here is the evaluation
    position `i`, which is what makes it a two-variable property and the
    reason it exercises the pebble rather than the summary alone.
    """
    result = []
    for n in (1, 2, 3, 4, 5, 6, 7, 8):
        alphabet = _bit_alphabet(n + 1)
        antecedent = andb(*(_agree(k) for k in range(1, n + 1)))
        formula = Hist(orb(notb(antecedent), _agree(0)))
        result.append(Benchmark(
            f"markey_agreement/n={n}", "markey_agreement",
            {"n": n, "sigma_size": len(alphabet)}, alphabet, formula, True,
            f"every position agreeing with the anchor on p_1..p_{n} agrees on p_0",
            provenance="Markey 2003 (Bull. EATCS 79), after Etessami-Vardi-Wilke; "
                       "gap proved in Laroussinie-Markey-Schnoebelen, LICS 2002",
            # Markey's lower bound is 2^(2^n) states for this language, so the
            # explicit DFA is out of reach almost immediately; cap the metric
            # rather than let `record` chase it.  A capped run is reported as
            # `compilation_timeout`, not as a small automaton.
            extra_metrics={"compile_state_cap": 256, "monoid_cap": 256} if n >= 2 else {}))
    return result


def gastin_oddoux_chain() -> list[Benchmark]:
    """Each p_1 is preceded by p_2, itself preceded by p_3, and so on.

    The scaling family of Gastin and Oddoux (MFCS 2003), used there to
    separate their on-the-fly 2VWAA-to-GBA construction from the naive one;
    their own table stops at n = 5, where the naive construction needs
    ~130,000 seconds.  Stated there as a negation over infinite words; the
    finite-word past reading drops the outer negation, so the benchmark is
    the positive chain property.
    """
    result = []
    for n in range(2, 9):
        alphabet = _bit_alphabet(n)
        chain: Unary = Bit(n - 1)
        for k in range(n - 2, 0, -1):
            chain = and1(Bit(k), Once(AtJ(chain)))
        # `Hist` only quantifies over positions strictly before the anchor,
        # so a genuine "at every position" property has to conjoin the
        # anchor's own instance.
        good = or1(not1(Bit(0)), Once(AtJ(chain)))
        formula = and1(good, Hist(AtJ(good)))
        result.append(Benchmark(
            f"gastin_oddoux_chain/n={n}", "gastin_oddoux_chain",
            {"n": n, "sigma_size": len(alphabet)}, alphabet, formula, True,
            f"every p_0 is preceded by a p_1 preceded by ... preceded by p_{n-1}",
            provenance="Gastin & Oddoux, MFCS 2003, section 5 (scaling family)",
            extra_metrics={"compile_state_cap": 256, "monoid_cap": 256} if n >= 4 else {}))
    return result


def gastin_oddoux_depth() -> list[Benchmark]:
    """The Gastin-Oddoux chain scaled by nesting depth, not proposition count.

    `gastin_oddoux_chain` is faithful to the source -- one proposition per
    link -- which pins the alphabet at `2^n` and so stops being writable
    past `n = 8`.  The quantity their benchmark actually scales is the
    *nesting depth* of the past operators, and that is separable from the
    alphabet: cycling a fixed set of letters through the links, exactly as
    `dot_depth` does, keeps `|Sigma|` constant while the chain grows.  The
    language differs from the source's (links repeat letters rather than
    naming distinct propositions), so these are a depth-scaling companion to
    the faithful family above, not a replacement for it.
    """
    result = []
    alphabet = ("a", "b")
    for k in (1, 10, 50, 100, 200, 500):
        links = tuple(alphabet[i % len(alphabet)] for i in range(k + 1))
        chain: Unary = Letter(links[k])
        for index in range(k - 1, 0, -1):
            chain = and1(Letter(links[index]), Once(AtJ(chain)))
        good = or1(not1(Letter(links[0])), Once(AtJ(chain)))
        formula = and1(good, Hist(AtJ(good)))
        result.append(Benchmark(
            f"gastin_oddoux_depth/k={k}", "gastin_oddoux_depth",
            {"k": k, "sigma_size": len(alphabet)}, alphabet, formula, True,
            f"every {links[0]!r} is preceded by a chain of {k} alternating letters",
            provenance="Gastin & Oddoux, MFCS 2003, section 5 (scaling family), "
                       "reparametrised by nesting depth over a fixed alphabet",
            extra_metrics={"compile_state_cap": 256, "monoid_cap": 256} if k >= 10 else {}))
    return result


def literature_benchmarks() -> list[Benchmark]:
    return markey_agreement() + gastin_oddoux_chain() + gastin_oddoux_depth()
