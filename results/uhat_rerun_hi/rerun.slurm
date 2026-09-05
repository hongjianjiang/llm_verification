#!/bin/bash
#SBATCH -c 2
#SBATCH --mem=8G
#SBATCH -t 04:00:00
#SBATCH -J uhat-rerun
# Wrapper: sets the knobs inside the job, because this cluster rejects
# --export (it requeues the array held with "user env retrieval failed").
set -euo pipefail
cd "${SLURM_SUBMIT_DIR:-$PWD}"
export RESTARTS=12
export STEPS=1500
exec bash scripts/uhat_sweep.slurm "$@"
