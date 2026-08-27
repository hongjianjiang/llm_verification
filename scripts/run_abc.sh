#!/usr/bin/env bash
# Run the ABC route (2LTL -> PVWAA -> circuit -> abc pdr) over .ltl examples,
# one CSV row per instance: verdict plus the --timing phase breakdown.
#
#   scripts/run_abc.sh                        # every examples/ltl/*.ltl
#   scripts/run_abc.sh examples/ltl/two_var_*.ltl   # just these
#   TIMEOUT=60 scripts/run_abc.sh             # longer budget
#
# Env: JAR, ABC_BIN, TIMEOUT (seconds), OUT (csv path), REPS (median of N).
set -uo pipefail

JAR=${JAR:-target/scala-3.5.1/brasp-verification.jar}
ABC_BIN=${ABC_BIN:-../abc/abc}
TIMEOUT=${TIMEOUT:-120}
OUT=${OUT:-results/abc_examples.csv}
REPS=${REPS:-1}

# GNU timeout is `gtimeout` where coreutils is installed under its g- prefix.
if command -v timeout >/dev/null 2>&1; then TIMEOUT_BIN=timeout
elif command -v gtimeout >/dev/null 2>&1; then TIMEOUT_BIN=gtimeout
else echo "run_abc.sh: need GNU timeout (brew install coreutils)" >&2; exit 1
fi

[ -f "$JAR" ] || { echo "run_abc.sh: no jar at $JAR — run 'sbt assembly' first" >&2; exit 1; }
[ -x "$ABC_BIN" ] || { echo "run_abc.sh: no abc binary at $ABC_BIN — set ABC_BIN" >&2; exit 1; }

files=("$@")
if [ ${#files[@]} -eq 0 ]; then files=(examples/ltl/*.ltl); fi

mkdir -p "$(dirname "$OUT")"
# Build into a private temp file and move it into place at the end: two runs
# sharing an $OUT would otherwise interleave rows (and the second's header
# truncation would silently discard the first's), and an interrupted run
# would leave a half-written CSV where a complete one used to be.
TMP=$(mktemp "${OUT}.XXXXXX") || { echo "run_abc.sh: could not create temp file next to $OUT" >&2; exit 1; }
trap 'rm -f "$TMP"' EXIT
printf 'instance,verdict,compile_s,encode_s,abc_s,total_s,wall_s\n' > "$TMP"

# Median of a whitespace-separated list, to one decimal.
median() { tr ' ' '\n' <<< "$1" | sort -n | awk '{v[NR]=$1} END{print (NR%2)?v[(NR+1)/2]:(v[NR/2]+v[NR/2+1])/2}'; }

decided=0; failed=0; skipped=0
for f in "${files[@]}"; do
  # The glob is expanded once, up front; a file can disappear before the
  # loop reaches it if something else is editing the corpus concurrently.
  [ -f "$f" ] || { echo "run_abc.sh: skipping missing $f" >&2; skipped=$((skipped+1)); continue; }
  stem=$(basename "$f" .ltl)
  walls=""; verdict=""; compile=""; encode=""; abc=""; total=""
  for _ in $(seq "$REPS"); do
    start=$(date +%s.%N)
    out=$("$TIMEOUT_BIN" "$TIMEOUT" java -jar "$JAR" "$f" --run-abc --abc-bin "$ABC_BIN" --timing 2>&1)
    code=$?
    wall=$(awk -v a="$(date +%s.%N)" -v b="$start" 'BEGIN{printf "%.1f", a-b}')
    walls="$walls $wall"

    if   [ $code -eq 124 ];                        then v=TIMEOUT
    elif grep -q "NOT PROVED" <<< "$out";          then v=NOT_PROVED
    elif grep -q "PROVED"     <<< "$out";          then v=PROVED
    elif grep -q "^translator:" <<< "$out";        then v=ERROR
    else                                                v=UNKNOWN
    fi
    [ -z "$verdict" ] && verdict=$v
    # A verdict that moves between repetitions is worth seeing, not averaging.
    [ "$v" != "$verdict" ] && verdict="MIXED:$verdict/$v"

    # timing: compile 0.82s  encode 1.65s  abc 0.47s  total 2.93s
    line=$(grep '^timing:' <<< "$out" | tail -1)
    if [ -n "$line" ]; then
      compile=$(sed -n 's/.*compile \([0-9.]*\)s.*/\1/p' <<< "$line")
      encode=$( sed -n 's/.*encode \([0-9.]*\)s.*/\1/p'  <<< "$line")
      abc=$(    sed -n 's/.*abc \([0-9.]*\)s.*/\1/p'     <<< "$line")
      total=$(  sed -n 's/.*total \([0-9.]*\)s.*/\1/p'   <<< "$line")
    fi
  done

  wall=$(median "${walls# }")
  printf '%s,%s,%s,%s,%s,%s,%s\n' "$stem" "$verdict" "$compile" "$encode" "$abc" "$total" "$wall" >> "$TMP"
  printf '%-44s %-12s %ss\n' "$stem" "$verdict" "$wall" >&2
  case "$verdict" in PROVED|NOT_PROVED) decided=$((decided+1));; *) failed=$((failed+1));; esac
done

mv "$TMP" "$OUT"
trap - EXIT

echo >&2
echo "decided $decided, not decided $failed, $skipped vanished mid-run  ->  $OUT" >&2
