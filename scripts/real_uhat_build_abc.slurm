#!/bin/bash
#SBATCH --partition=cpu
#SBATCH --account=cpu
#SBATCH --qos=cpu
#SBATCH --cpus-per-task=4
#SBATCH --mem=8G
#SBATCH --time=01:00:00
#SBATCH --job-name=study-abc
set -euo pipefail
cd "$SLURM_SUBMIT_DIR"
build_dir=$(mktemp -d /tmp/real-uhat-abc.XXXXXX)
trap 'rm -rf "$build_dir"' EXIT
tar -xzf abc-source.tar.gz -C "$build_dir"
make -C "$build_dir" -j "$SLURM_CPUS_PER_TASK" ABC_USE_NO_READLINE=1
mkdir -p bin
cp "$build_dir/abc" bin/abc
bin/abc -c 'version'
