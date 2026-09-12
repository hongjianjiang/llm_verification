#!/bin/bash
#SBATCH --partition=cpu
#SBATCH --account=cpu
#SBATCH --qos=cpu
#SBATCH --cpus-per-task=2
#SBATCH --mem=8G
#SBATCH --time=00:10:00
#SBATCH --array=0-7%4
#SBATCH --job-name=uhat-recheck
set -euo pipefail
cd "$SLURM_SUBMIT_DIR"
export OMP_NUM_THREADS="$SLURM_CPUS_PER_TASK"
export MKL_NUM_THREADS="$SLURM_CPUS_PER_TASK"
original=/home/hongjian/llm_verification/experiments/real_uhat_20260911
exec /home/hongjian/uhat-venv/bin/python -u scripts/real_uhat_recheck.py \
    --original "$original/results/real_uhat_systematic_20260911" \
    --out results --legacy-model "$original/uhat/real_model.py" \
    --jar "$original/bin/brasp-verification.jar" --abc "$original/bin/abc" \
    --index "$SLURM_ARRAY_TASK_ID" --shards 8
