#!/usr/bin/env bash
# Run the 1LTL route (2LTL -> eliminate the second variable -> explicit DFA)
# over .ltl examples, one CSV row per instance.  This is the classical
# baseline that scripts/run_abc.sh is measured against; the columns differ
# because the phases do -- there is no circuit to encode, and the search is
# an explicit forward exploration rather than a call to a model checker.
#
#   scripts/run_1ltl.sh                        # every examples/ltl/*.ltl
#   scripts/run_1ltl.sh examples/ltl/markey_agreement__n*.ltl   # just these
#   TIMEOUT=60 scripts/run_1ltl.sh             # longer budget
#
# Env: JAR, TIMEOUT (seconds), OUT (csv path), REPS (median of N),
#      MAX_STATES (--native-max-states budget).
set -uo pipefail

JAR=${JAR:-target/scala-3.5.1/brasp-verification.jar}
TIMEOUT=${TIMEOUT:-120}
OUT=${OUT:-results/1ltl_examples.csv}
REPS=${REPS:-1}
MAX_STATES=${MAX_STATES:-50000000}

# GNU timeout is `gtimeout` where coreutils is installed under its g- prefix.
if command -v timeout >/dev/null 2>&1; then TIMEOUT_BIN=timeout
elif command -v gtimeout >/dev/null 2>&1; then TIMEOUT_BIN=gtimeout
else echo "run_1ltl.sh: need GNU timeout (brew install coreutils)" >&2; exit 1
fi

[ -f "$JAR" ] || { echo "run_1ltl.sh: no jar at $JAR — run 'sbt assembly' first" >&2; exit 1; }

files=("$@")
if [ ${#files[@]} -eq 0 ]; then files=(examples/ltl/*.ltl); fi

mkdir -p "$(dirname "$OUT")"
# Build into a private temp file and move it into place at the end: two runs
# sharing an $OUT would otherwise interleave rows (and the second's header
# truncation would silently discard the first's), and an interrupted run
# would leave a half-written CSV where a complete one used to be.
TMP=$(mktemp "${OUT}.XXXXXX") || { echo "run_1ltl.sh: could not create temp file next to $OUT" >&2; exit 1; }
trap 'rm -f "$TMP"' EXIT
printf 'instance,verdict,compile_s,explore_s,total_s,wall_s,disjuncts\n' > "$TMP"

# Median of a whitespace-separated list, to one decimal.
median() { tr ' ' '\n' <<< "$1" | sort -n | awk '{v[NR]=$1} END{print (NR%2)?v[(NR+1)/2]:(v[NR/2]+v[NR/2+1])/2}'; }

decided=0; blown=0; failed=0; skipped=0
for f in "${files[@]}"; do
  # The glob is expanded once, up front; a file can disappear before the
  # loop reaches it if something else is editing the corpus concurrently.
  [ -f "$f" ] || { echo "run_1ltl.sh: skipping missing $f" >&2; skipped=$((skipped+1)); continue; }
  stem=$(basename "$f" .ltl)
  walls=""; verdict=""; compile=""; explore=""; total=""; disjuncts=""
  for _ in $(seq "$REPS"); do
    start=$(date +%s.%N)
    out=$("$TIMEOUT_BIN" -k 10 "$TIMEOUT" java -jar "$JAR" "$f" \
            --one-variable --run-native --native-max-states "$MAX_STATES" --timing 2>&1)
    code=$?
    wall=$(awk -v a="$(date +%s.%N)" -v b="$start" 'BEGIN{printf "%.1f", a-b}')
    walls="$walls $wall"

    # BLOWUP is kept apart from ERROR on purpose: it is this route's
    # characteristic failure -- the one-variable case split refusing before
    # any automaton exists -- and it is the result, not a defect.  The paper
    # marks these separately too.
    if   [ $code -eq 124 ];                                   then v=TIMEOUT
    elif grep -q "case split over .* anchor references" <<< "$out"; then v=BLOWUP
    elif grep -q "NOT PROVED"   <<< "$out";                   then v=NOT_PROVED
    elif grep -q "UNKNOWN"      <<< "$out";                   then v=UNKNOWN
    elif grep -q "PROVED"       <<< "$out";                   then v=PROVED
    elif grep -q "^translator:" <<< "$out";                   then v=ERROR
    else                                                           v=UNKNOWN
    fi
    [ -z "$verdict" ] && verdict=$v
    # A verdict that moves between repetitions is worth seeing, not averaging.
    [ "$v" != "$verdict" ] && verdict="MIXED:$verdict/$v"

    # How wide the refused case split was -- the size of the exponential this
    # route pays and the two-variable form avoids.
    if [ "$v" = BLOWUP ]; then
      disjuncts=$(sed -n 's/.*case split over \([0-9]*\) anchor references.*/\1/p' <<< "$out" | head -1)
    fi

    # timing: compile 0.39s  explore 0.39s  total 0.78s
    line=$(grep '^timing:' <<< "$out" | tail -1)
    if [ -n "$line" ]; then
      compile=$(sed -n 's/.*compile \([0-9.]*\)s.*/\1/p' <<< "$line")
      explore=$(sed -n 's/.*explore \([0-9.]*\)s.*/\1/p' <<< "$line")
      total=$(  sed -n 's/.*total \([0-9.]*\)s.*/\1/p'   <<< "$line")
    fi
  done

  wall=$(median "${walls# }")
  printf '%s,%s,%s,%s,%s,%s,%s\n' "$stem" "$verdict" "$compile" "$explore" "$total" "$wall" "$disjuncts" >> "$TMP"
  printf '%-44s %-12s %ss%s\n' "$stem" "$verdict" "$wall" \
    "$([ -n "$disjuncts" ] && echo "  (2^$disjuncts disjuncts)")" >&2
  case "$verdict" in
    PROVED|NOT_PROVED) decided=$((decided+1));;
    BLOWUP)            blown=$((blown+1));;
    *)                 failed=$((failed+1));;
  esac
done

mv "$TMP" "$OUT"
trap - EXIT

echo >&2
echo "decided $decided, blew up $blown, otherwise not decided $failed, $skipped vanished mid-run  ->  $OUT" >&2
