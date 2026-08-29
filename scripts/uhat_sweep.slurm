#!/bin/bash
#SBATCH -p cpu20
#SBATCH -c 2
#SBATCH --mem=4G
#SBATCH -t 04:00:00
#SBATCH -J uhat
#SBATCH -o results/uhat_sweep/logs/%A_%a.log
#
# One array element = one (task, layers, heads, terms) cell of the sweep.
#
# Runs on CPU or GPU: if Slurm allocated a GPU, the script installs CUDA
# wheels and trains on it, otherwise CPU wheels. Measured on an A100 against
# 4 cpu cores, per training step: 3.5x on the small star-free tasks, 13x at
# dot_depth k=5, 31x at k=8 -- the pairwise attention predicates are
# batch*n^2*terms elementwise work, which grows fast with word length.
#
# For the sweep as a whole CPU is still the right default: 270 independent
# jobs at 40-way concurrency beats a 3-4x speedup on the one or two GPUs this
# cluster will actually give you. Submit the long-word dot_depth cells to a
# GPU instead, where the speedup is 13-31x:
#
#   sbatch -p gpu22 --gres=gpu:1 --array=1-N scripts/uhat_sweep.slurm
#
#   scripts/uhat_sweep.py plan > results/uhat_sweep/grid.txt
#   mkdir -p results/uhat_sweep/logs
#   sbatch --array=1-$(wc -l < results/uhat_sweep/grid.txt) scripts/uhat_sweep.slurm
#   scripts/uhat_sweep.py collect
#
# Env: GRID, OUTDIR, STEPS, RESTARTS, BATCH, VENV_DIR, WHEEL_INDEX, DEVICE.
set -euo pipefail

# First positional arg wins: `sbatch ... uhat_sweep.slurm my_grid.txt`.
# Passing it this way avoids --export, which this cluster's sbatch rejects
# with "user env retrieval failed requeued held".
GRID="${1:-${GRID:-results/uhat_sweep/grid.txt}}"
OUTDIR="${OUTDIR:-results/uhat_sweep}"
STEPS="${STEPS:-1500}"
RESTARTS="${RESTARTS:-3}"
BATCH="${BATCH:-0}"   # 0 = full batch; set e.g. 512 for the long-word dot_depth cells
# A GPU allocation implies CUDA wheels and cuda training; no allocation
# implies the much smaller CPU wheels. Auto-detected so that switching
# partitions needs no --export (which this cluster's sbatch rejects).
if [ -n "${SLURM_JOB_GPUS:-}${SLURM_GPUS_ON_NODE:-}" ]; then
  WHEEL_INDEX="${WHEEL_INDEX:-cu126}"
  DEVICE="${DEVICE:-cuda}"
else
  WHEEL_INDEX="${WHEEL_INDEX:-cpu}"
  DEVICE="${DEVICE:-cpu}"
fi

# Venv on the compute node's own /tmp, so nothing lands on the home quota.
# Node-local means it is invisible to other nodes -- that is fine, each node
# builds its own once and every later element on that node reuses it.
TMP_BASE="${TMP_BASE:-/tmp/$USER/uhat}"
VENV_DIR="${VENV_DIR:-$TMP_BASE/venv-$WHEEL_INDEX}"   # cpu and cuda venvs must not collide

# Keep each element inside its cpu allocation; torch otherwise sizes its
# thread pool from the whole node and array elements thrash each other.
export OMP_NUM_THREADS="${SLURM_CPUS_PER_TASK:-1}"
export MKL_NUM_THREADS="$OMP_NUM_THREADS"

cd "${SLURM_SUBMIT_DIR:-$PWD}"
[ -f "$GRID" ] || { echo "no grid at $GRID -- run 'scripts/uhat_sweep.py plan' first" >&2; exit 1; }
: "${SLURM_ARRAY_TASK_ID:?set SLURM_ARRAY_TASK_ID to run this outside an array job}"

mkdir -p "$TMP_BASE/pip-cache" "$TMP_BASE/pip-tmp"
export PIP_CACHE_DIR="$TMP_BASE/pip-cache"
export TMPDIR="$TMP_BASE/pip-tmp"

# Several array elements can start on the same node at the same moment, so
# the create-or-reuse has to be serialised -- otherwise two of them install
# into the same prefix concurrently and both end up with a broken venv.
echo "[1/3] venv $VENV_DIR on $(hostname)"
exec 9>"$TMP_BASE/.venv.lock"
flock 9
if [[ -x "$VENV_DIR/bin/python" ]] && "$VENV_DIR/bin/python" -c "import torch" 2>/dev/null; then
  echo "  [reuse] venv already built on this node"
else
  rm -rf "$VENV_DIR"
  python3 -m venv "$VENV_DIR"
  "$VENV_DIR/bin/python" -m pip install --no-cache-dir -q -U pip
  "$VENV_DIR/bin/python" -m pip install --no-cache-dir -q \
    --index-url "https://download.pytorch.org/whl/${WHEEL_INDEX}" \
    --extra-index-url https://pypi.org/simple \
    torch
  # torch warns on every import without it; nothing here needs numpy otherwise.
  "$VENV_DIR/bin/python" -m pip install --no-cache-dir -q numpy
fi
flock -u 9
exec 9>&-

PYTHON="$VENV_DIR/bin/python"
echo "[2/3] $("$PYTHON" -c 'import torch,sys; print("python", sys.version.split()[0], "torch", torch.__version__)')"

line=$(sed -n "${SLURM_ARRAY_TASK_ID}p" "$GRID")
[ -n "$line" ] || { echo "no line ${SLURM_ARRAY_TASK_ID} in $GRID" >&2; exit 1; }
read -r task layers heads terms <<< "$line"

# A grid entry is either a built-in task name or a path to a .brasp program.
if [[ "$task" == *.brasp ]]; then
  TASK_ARGS=(--brasp "$task")
  base="$(basename "$task" .brasp)"
else
  TASK_ARGS=(--task "$task")
  base="$task"
fi
stem="${base}__l${layers}_h${heads}_t${terms}"
mkdir -p "$OUTDIR/programs"

echo "[3/3] $stem (array ${SLURM_ARRAY_TASK_ID}, device=$DEVICE, $OMP_NUM_THREADS threads)"
"$PYTHON" -u -m uhat.train \
  "${TASK_ARGS[@]}" --layers "$layers" --heads "$heads" --terms "$terms" \
  --steps "$STEPS" --restarts "$RESTARTS" --batch "$BATCH" --device "$DEVICE" --quiet \
  --out "$OUTDIR/programs/${stem}.brasp" \
  --json-out "$OUTDIR/${stem}.json"
