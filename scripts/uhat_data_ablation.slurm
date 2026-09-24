#!/bin/bash
#SBATCH -p gpu-rtx8000
#SBATCH -c 2
#SBATCH --gres=gpu:1
#SBATCH --mem=12G
#SBATCH -t 08:00:00
#SBATCH -J uhat-data
set -euo pipefail
cd "${SLURM_SUBMIT_DIR:?}"
ROOT="${1:-results/uhat_data_ablation_20260921}"
export OMP_NUM_THREADS="${SLURM_CPUS_PER_TASK:-2}"
export MKL_NUM_THREADS="$OMP_NUM_THREADS"
TMP_BASE="/tmp/$USER/uhat"
VENV_DIR="$TMP_BASE/venv-cu126"
mkdir -p "$TMP_BASE/pip-tmp"
export TMPDIR="$TMP_BASE/pip-tmp"
exec 9>"$TMP_BASE/.venv.lock"
flock 9
if ! "$VENV_DIR/bin/python" -c 'import torch' 2>/dev/null; then
    python3 -m venv "$VENV_DIR"
    "$VENV_DIR/bin/python" -m pip install --no-cache-dir -q -U pip
    "$VENV_DIR/bin/python" -m pip install --no-cache-dir -q \
        --index-url https://download.pytorch.org/whl/cu126 \
        --extra-index-url https://pypi.org/simple torch numpy
fi
flock -u 9
exec 9>&-
"$VENV_DIR/bin/python" -c 'import torch; assert torch.cuda.is_available(); print(torch.__version__, torch.cuda.get_device_name())'
"$VENV_DIR/bin/python" -u scripts/uhat_data_ablation.py run \
    --root "$ROOT" --index "${SLURM_ARRAY_TASK_ID:?}" --device cuda
