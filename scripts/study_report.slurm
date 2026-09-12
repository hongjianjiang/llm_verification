#!/bin/bash
#SBATCH --partition=cpu
#SBATCH --account=cpu
#SBATCH --qos=cpu
#SBATCH --cpus-per-task=1
#SBATCH --mem=1G
#SBATCH --time=00:05:00
#SBATCH --job-name=study-report
set -euo pipefail
cd "$SLURM_SUBMIT_DIR"
/home/hongjian/uhat-venv/bin/python scripts/study_report.py
sacct -j 50497517,50497533,50497534,50497546,50497569 --parsable2 --format=JobID,State,ExitCode,Elapsed,MaxRSS,AllocCPUS > scheduler-accounting.txt
