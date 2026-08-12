# Determinizing the Forward PVWAA: the Boolean-Summary Algorithm

This note documents how `BooleanAutomaton.scala` turns the recursive,
alternating `ForwardPVWAA` run (`Pvwaa.scala`) into a deterministic
automaton, and why its `Carry`/`Leave`/`Goto` leaves differ in sign from
the paper's own presentation.

## 1. Two automata, mirrored

The paper defines a **right-to-left** PVWAA for past-time 2LTL (`Since`):
the head starts at the end of the word and moves *leftward*.

This codebase implements the **directional mirror** of that automaton, for
future-time formulas (`Until`). The formula DAG is mirrored
(`Ltl.mirrorDag`) and the automaton reads left-to-right instead
(`Pvwaa.scala:5-9`). `BooleanAutomaton.scala` is in turn the directional
mirror of Section 9.2 of the paper (`BooleanAutomaton.scala:3-9`): it
reverses the forward PVWAA (which reads `reverse(L)` left-to-right) into
an automaton that reads the *original* word `L` left-to-right.

Because of this mirroring, a step that is `head - 1` in the paper's
right-to-left automaton becomes `head + 1` here — same operation, opposite
sign, since the whole automaton was deliberately flipped.

## 2. Leaf translation: recursive search vs. table lookup

A configuration of the PVWAA is `(state, pebble, head)`. Each leaf of the
`AND`/`OR` transition formula `δ(q, symbol)` is resolved differently
depending on which automaton is doing the resolving:

| Leaf         | PVWAA move (recursive search)         | Boolean automaton (table lookup)         |
|--------------|----------------------------------------|-------------------------------------------|
| `Carry q'`   | `run(q', head + 1, head + 1)`          | `priorDiagonal(q')`                       |
| `Leave q'`   | `run(q', pebble, head + 1)`            | `summary.table(q')(β)`                    |
| `Goto q'`    | `run(q', pebble, pebble)`              | `β` at `q'`'s index in the goto-support   |

- **Carry** drops a fresh pebble at the new head position and moves both
  pointers forward together. In the deterministic automaton there is no
  "next position" to recurse into yet — instead we read off the value
  already solved for `q'` at the *current* position via the diagonal
  (§3.2), computed *before* consuming the symbol.
- **Leave** keeps the pebble where it is and advances only the head. This
  becomes a plain table lookup at the same abstraction `β`, in the summary
  computed for the *previous* symbol.
- **Goto** snaps the head back to the pebble. Since the abstraction `β` is
  defined as "the truth values of every goto-support state at the
  pebble's position," this is just indexing into `β` — no automaton state
  is consulted at all.

(`Pvwaa.scala:283-285`, `BooleanAutomaton.scala:96-99`)

## 3. The deterministic algorithm

### 3.1 State space

```
Q   = PVWAA states, linearly ordered by rank (the very-weak order)
F   = final states
G   = goto-support = { q' : q' appears as a Goto target in some δ(q, a) },
      sorted by rank
B^G = all boolean assignments β : G -> 𝔹     (2^|G| of them)
```

A **Boolean summary** is a total function `S : Q x B^G -> 𝔹`. Informally,
`S(q, β)` answers: "if the pebble sat at the current position, and every
goto-support state's truth value there were given by `β`, would `q`
accept from here?" One `S` replaces the entire recursive
`run(q', pebble, head)` search at a fixed `head`.

The full state space has size `2^(|Q| * 2^|G|)`
(`maximumStateCount`, `BooleanAutomaton.scala:112-113`) — astronomical by
construction (the paper's Prop. 9.4) and never meant to be materialized.
The algorithm below only ever holds **one** summary `S` at a time.

### 3.2 Diagonal — resolving self-reference within one summary

`V(q) = S(q, V|_G)` looks circular, but very-weakness guarantees that any
row of `S` only inspects **strictly lower-ranked** goto states, so a
single ascending sweep resolves it — no fixpoint iteration needed:

```
diagonal(S):
  V := {}                                        # partial map
  for q in Q sorted by rank ascending:
    β := [ V.getOrElse(g, false) for g in G ]     # lower-ranked g: real value
                                                   # higher-ranked g: don't-care
    V[q] := S(q, β)
  return V
```

(`BooleanAutomaton.scala:72-82`)

### 3.3 Transition — consuming one symbol

Given the summary `S` computed *before* reading symbol `a`, and
`V := diagonal(S)` (the "prior diagonal"):

```
transition(S, a):
  V := diagonal(S)
  for q in Q:
    for β in B^G:
      S'(q, β) := eval(δ(q, a), β)
  return S'

eval(formula, β):
  match formula:
    Constant(b)  -> b
    And(fs)      -> all(eval(f, β) for f in fs)
    Or(fs)       -> any(eval(f, β) for f in fs)
    Carry q'     -> V(q')            # prior diagonal
    Leave q'     -> S(q', β)         # same β, prior table
    Goto q'      -> β(q')            # index into current abstraction
```

(`BooleanAutomaton.scala:88-104`)

### 3.4 Initial state and acceptance

```
S₀(q, β) := [q ∈ F]        # independent of β — matches head == |word| in the PVWAA

accepts(word):
  S := S₀
  for a in word:
    S := transition(S, a)
  return diagonal(S)(q₀)   # q₀ = PVWAA initial state
```

(`BooleanAutomaton.scala:63-69, 108-110`)

Each `S` is one state of an honest deterministic automaton over the
alphabet `Σ`.

## 4. Materializing a concrete finite DFA (lazy reachability)

Only the *reachable* summaries matter in practice, so `reachable` does a
plain BFS over the transition function of §3.3, deduplicating identical
`BooleanSummary` values by structural equality — exactly the strategy the
paper suggests for emptiness checking ("one explores the reachable
summaries of `A→` lazily... never materializing the state space"):

```
reachable(maxStates):
  ids := {}                            # BooleanSummary -> Int, insertion order
  id(S) := ids.getOrElseUpdate(S, ids.size)

  initial := id(S₀)
  queue := [S₀]
  transitions := {}

  while queue nonempty:
    current := queue.dequeue()
    for a in Σ:
      next := transition(current, a)
      if next is new and |ids| >= maxStates:
        mark truncated; skip
      else:
        transitions[(id(current), a)] := id(next)
        if next is new: queue += next

  accepting := { id(S) : diagonal(S)(q₀) = true }
  return DFA(states = ids, transitions, accepting, initial, truncated)
```

(`BooleanAutomaton.scala:139-161`)

This is what actually gets exported to Kind2/Lustre and rendered as the
`.dot` graph — a genuine finite DFA, capped at `maxStates` (default 512)
as a safety valve. The theoretical bound (§3.1) is doubly exponential, but
reachable state counts stay small for real specifications in practice.

## 5. Source references

| Concept                     | Location                                  |
|------------------------------|--------------------------------------------|
| Directional mirroring note   | `Pvwaa.scala:5-9`, `BooleanAutomaton.scala:3-9` |
| PVWAA recursive run           | `Pvwaa.scala:266-287`                      |
| Goto-support computation      | `BooleanAutomaton.scala:51-60`              |
| Initial Boolean summary       | `BooleanAutomaton.scala:63-69`              |
| `diagonal`                    | `BooleanAutomaton.scala:72-82`              |
| `transition`                  | `BooleanAutomaton.scala:85-105`             |
| `accepts`                     | `BooleanAutomaton.scala:108-110`            |
| `reachable` (finite DFA)      | `BooleanAutomaton.scala:139-161`            |
