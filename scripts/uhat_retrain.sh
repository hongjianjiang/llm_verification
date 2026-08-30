#!/usr/bin/env bash
# Retrain every solved language at its best known architecture.
#
#   scripts/uhat_retrain.sh                    # here, one after another
#   SLURM=1 scripts/uhat_retrain.sh            # as a Slurm array, on cpu20
#   SLURM=1 GPU=1 scripts/uhat_retrain.sh      # ... on gpu22 instead
#
# Prefer GPU when the run is small enough that every element starts at once.
# Array concurrency only beats a per-element speedup while elements are
# queueing behind each other; with 28 elements and 40-way concurrency they
# all start immediately, so wall-clock is just the slowest element -- and the
# slowest are the deep dot_depth cells, exactly where the GPU is 13-31x
# faster (see scripts/uhat_bench.py). The sweep runner picks the device up
# from the allocation on its own.
#
# The architecture comes from results/uhat_best.csv, which
# `scripts/uhat_sweep.py best --out` writes: for each language, the smallest
# layers/heads/terms that reached 1.0 on both the training words and the
# longer held-out ones. Restarts default higher than the sweep used, because
# several of these solved in only 1 of 18 configurations -- at their best
# architecture the remaining variance is the random restart, not the shape.
#
# Env: BEST, OUTDIR, STEPS, RESTARTS, PYTHON, SLURM, JOBS.
set -euo pipefail

BEST="${BEST:-results/uhat_best.csv}"
OUTDIR="${OUTDIR:-results/uhat_best}"
STEPS="${STEPS:-1500}"
RESTARTS="${RESTARTS:-5}"
PYTHON="${PYTHON:-python3}"
JOBS="${JOBS:-40}"
if [ -n "${GPU:-}" ]; then
  PARTITION_ARGS=(-p "${PARTITION:-gpu22}" --gres=gpu:1 -c 4 --mem=16G)
else
  PARTITION_ARGS=()
fi

[ -f "$BEST" ] || {
  echo "uhat_retrain.sh: no $BEST — run 'scripts/uhat_sweep.py best --out $BEST' first" >&2
  exit 1
}

mkdir -p "$OUTDIR/programs"

# The grid format the sweep runner already understands: one
# "<task-or-path> <layers> <heads> <terms>" per line.
GRID="$OUTDIR/grid_best.txt"
# `tr -d '\r'` is not paranoia: csv.DictWriter's default dialect terminates
# rows with \r\n, and a trailing \r turns every task name into one the
# trainer rejects.
tail -n +2 "$BEST" | tr -d '\r' | awk -F, 'NF >= 9 {
  print ($9 == "" ? $1 : $9), $2, $3, $4
}' > "$GRID"
# Fail here rather than discovering it 28 job failures later.
while read -r task layers heads terms rest; do
  [ -n "$terms" ] && [ -z "$rest" ] || { echo "uhat_retrain.sh: malformed grid line: $task $layers $heads $terms $rest" >&2; exit 1; }
  case "$task" in *[!a-zA-Z0-9_/.-]*) echo "uhat_retrain.sh: suspicious task field: $(printf %q "$task")" >&2; exit 1;; esac
done < "$GRID"
count=$(wc -l < "$GRID")
echo "retraining $count languages at their best architecture (steps=$STEPS restarts=$RESTARTS)"

if [ -n "${SLURM:-}" ]; then
  mkdir -p "$OUTDIR/logs"
  # Reuse the sweep runner: it already builds the venv, pins threads to the
  # allocation, and writes the same .brasp/.json pair per cell.
  exec sbatch --array="1-${count}%${JOBS}" \
    --output="$OUTDIR/logs/%A_%a.log" \
    "${PARTITION_ARGS[@]}" \
    scripts/uhat_sweep.slurm "$GRID" "$OUTDIR"
fi

failed=0
while read -r task layers heads terms; do
  if [[ "$task" == *.brasp ]]; then
    args=(--brasp "$task"); base="$(basename "$task" .brasp)"
  else
    args=(--task "$task"); base="$task"
  fi
  # Same stem the Slurm path writes, so the two produce interchangeable
  # output and scripts/uhat_verify.py finds either without a --flat flag.
  base="${base}__l${layers}_h${heads}_t${terms}"
  printf '%-40s l%s h%s t%s ... ' "$base" "$layers" "$heads" "$terms"
  if "$PYTHON" -u -m uhat.train "${args[@]}" \
       --layers "$layers" --heads "$heads" --terms "$terms" \
       --steps "$STEPS" --restarts "$RESTARTS" --quiet \
       --out "$OUTDIR/programs/${base}.brasp" \
       --json-out "$OUTDIR/${base}.json" > "$OUTDIR/${base}.log" 2>&1; then
    acc=$(sed -n 's/.*longer-word test \([0-9.]*\).*/\1/p' "$OUTDIR/${base}.log" | tail -1)
    echo "test ${acc:-?}"
  else
    echo "FAILED (see $OUTDIR/${base}.log)"
    failed=$((failed + 1))
  fi
done < "$GRID"

echo
echo "done; $failed failed"
exit $([ "$failed" -eq 0 ] && echo 0 || echo 1)
