#!/bin/bash
#SBATCH -p cpu20
#SBATCH -t 10:00
#SBATCH --cpus-per-task=1
#SBATCH --mem=6G
#SBATCH -a 0-93
#SBATCH -J ltl-scaling
#SBATCH -o results/ltl_scaling_20260912/slurm/%A_%a.out

set -euo pipefail
cd "$SLURM_SUBMIT_DIR"

exec /home/hongjian/uhat-venv/bin/python -u scripts/ltl_scaling_study.py run \
  --out results/ltl_scaling_20260912 \
  --cell "$SLURM_ARRAY_TASK_ID" \
  --jar bin/circuit-study.jar \
  --abc bin/abc \
  --timeout 120 \
  --repetitions 3
