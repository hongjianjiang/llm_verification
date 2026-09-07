"""Minimal DFA helpers shared by the family verifier (import shim)."""
import importlib.util
from pathlib import Path

_spec = importlib.util.spec_from_file_location("_ud", Path(__file__).resolve().parent / "scripts" / "uhat_dfa.py")
_ud = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(_ud)


def candidate_dfa(alphabet, accepts, suffix_length, cap=4000):
    return _ud.dfa_from_predicate(tuple(alphabet), accepts, suffix_length, cap)


def same_language(alphabet, left, right) -> bool:
    if left is None or right is None:
        return False
    seen, frontier = {(0, 0)}, [(0, 0)]
    while frontier:
        p, q = frontier.pop()
        if (p in left.accepting) != (q in right.accepting):
            return False
        for index in range(len(alphabet)):
            step = (left.transitions[p][index], right.transitions[q][index])
            if step not in seen:
                seen.add(step); frontier.append(step)
    return True
