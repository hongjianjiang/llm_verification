#!/bin/bash
#SBATCH -p gpu22
#SBATCH -c 4
#SBATCH --gres=gpu:1
#SBATCH --mem=16G
#SBATCH -t 00:30:00
#SBATCH -J uhat_bench
#SBATCH -o results/uhat_bench_%j.log
#
# Times one training step on CPU and on GPU, same process, same shapes, so
# the comparison is like-for-like. Uses CUDA wheels in a node-local venv.
set -euo pipefail

CU_INDEX="${CU_INDEX:-cu126}"
TMP_BASE="${TMP_BASE:-/tmp/$USER/uhat_gpu}"
VENV_DIR="${VENV_DIR:-$TMP_BASE/venv}"

cd "${SLURM_SUBMIT_DIR:-$PWD}"
mkdir -p "$TMP_BASE/pip-cache" "$TMP_BASE/pip-tmp"
export PIP_CACHE_DIR="$TMP_BASE/pip-cache"
export TMPDIR="$TMP_BASE/pip-tmp"
export OMP_NUM_THREADS="${SLURM_CPUS_PER_TASK:-1}"

echo "[1/3] venv $VENV_DIR on $(hostname)"
exec 9>"$TMP_BASE/.venv.lock"
flock 9
if [[ -x "$VENV_DIR/bin/python" ]] && "$VENV_DIR/bin/python" -c "import torch" 2>/dev/null; then
  echo "  [reuse]"
else
  rm -rf "$VENV_DIR"
  python3 -m venv "$VENV_DIR"
  "$VENV_DIR/bin/python" -m pip install --no-cache-dir -q -U pip
  "$VENV_DIR/bin/python" -m pip install --no-cache-dir -q \
    --index-url "https://download.pytorch.org/whl/${CU_INDEX}" torch
  "$VENV_DIR/bin/python" -m pip install --no-cache-dir -q numpy
fi
flock -u 9
exec 9>&-

PYTHON="$VENV_DIR/bin/python"
# GPU first: if the time limit bites, it bites the baseline, not the number
# the job exists to produce.
LIMIT="${LIMIT:-4}"   # the 5th config retains ~6GB and is CPU-hostile
echo "[2/3] gpu"
"$PYTHON" scripts/uhat_bench.py --device cuda --limit "$LIMIT"
echo
echo "[3/3] cpu baseline (${OMP_NUM_THREADS} threads)"
"$PYTHON" scripts/uhat_bench.py --device cpu --limit "$LIMIT"
