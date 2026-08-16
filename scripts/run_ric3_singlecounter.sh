#!/usr/bin/env bash
# Launch rIC3 safety checks over every .ltl file in ltl_examples/SingleCounter,
# one background process per instance, and return immediately.
#
# These models can be large (the smallest SingleCounter instance already
# compiles to a multi-million-line BTOR2 model) and rIC3's IC3 search can run
# well past a minute, so this script does not block waiting for a verdict:
# it starts every instance under `nohup ... &`, detaches it from the shell,
# and writes each instance's output to its own log file. Use
# scripts/ric3_status.sh to check progress/results afterward.
#
# Usage:
#   scripts/run_ric3_singlecounter.sh [directory] [ric3-mode] [timeout-seconds]
#
# directory        defaults to ltl_examples/SingleCounter
# ric3-mode        "ic3" (default) or "portfolio", forwarded as --ric3-mode
# timeout-seconds  per-instance wall-clock cap, default 1800 (30 min);
#                   pass 0 to disable the cap entirely

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

dir="${1:-$repo_root/ltl_examples/SingleCounter}"
mode="${2:-ic3}"
timeout_secs="${3:-1800}"

jar="$repo_root/target/scala-3.5.1/brasp-verification.jar"
if [[ ! -f "$jar" ]]; then
  echo "error: $jar not found; build it first with: sbt assembly" >&2
  exit 1
fi

if [[ ! -d "$dir" ]]; then
  echo "error: no such directory: $dir" >&2
  exit 1
fi

timeout_cmd=()
if [[ "$timeout_secs" != "0" ]]; then
  if command -v timeout >/dev/null 2>&1; then
    timeout_cmd=(timeout "$timeout_secs")
  elif command -v gtimeout >/dev/null 2>&1; then
    timeout_cmd=(gtimeout "$timeout_secs")
  else
    echo "error: a per-instance timeout was requested but neither 'timeout' nor 'gtimeout' is available" >&2
    echo "       (pass 0 as the third argument to disable the cap)" >&2
    exit 1
  fi
fi

shopt -s nullglob
files=("$dir"/*.ltl)
shopt -u nullglob
if [[ ${#files[@]} -eq 0 ]]; then
  echo "error: no .ltl files found under $dir" >&2
  exit 1
fi

log_root="$repo_root/logs/ric3_singlecounter/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$log_root"
pid_file="$log_root/pids.txt"
: > "$pid_file"

for file in "${files[@]}"; do
  name="$(basename "$file" .ltl)"
  logfile="$log_root/$name.log"
  nohup "${timeout_cmd[@]}" java -cp "$jar" brasp.Translator "$file" --run-ric3 --ric3-mode "$mode" >"$logfile" 2>&1 &
  pid=$!
  disown "$pid" 2>/dev/null || true
  echo "$pid $name $logfile" >>"$pid_file"
  echo "started $name (pid $pid) -> $logfile"
done

echo
echo "All instances launched. Logs and pid list: $log_root"
echo "Check status with: scripts/ric3_status.sh $log_root"
