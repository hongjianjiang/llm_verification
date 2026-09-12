#!/bin/bash
#SBATCH --partition=cpu
#SBATCH --account=cpu
#SBATCH --qos=cpu
#SBATCH --cpus-per-task=1
#SBATCH --mem=6G
#SBATCH --time=01:00:00
#SBATCH --job-name=circuit-study
set -euo pipefail
cd "$SLURM_SUBMIT_DIR"
exec /home/hongjian/uhat-venv/bin/python -u scripts/circuit_study.py run --out results/circuit_study_20260911 --cell "$SLURM_ARRAY_TASK_ID" --jar bin/circuit-study.jar --abc bin/abc --timeout 120
