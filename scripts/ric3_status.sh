#!/usr/bin/env bash
# Report status/results for a batch of rIC3 runs started by
# scripts/run_ric3_singlecounter.sh.
#
# Usage:
#   scripts/ric3_status.sh <log-dir>       # e.g. logs/ric3_singlecounter/20260816_120000
#   scripts/ric3_status.sh                 # defaults to the most recent log-dir

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

log_root="${1:-}"
if [[ -z "$log_root" ]]; then
  log_root="$(ls -1dt "$repo_root"/logs/ric3_singlecounter/*/ 2>/dev/null | head -1 || true)"
  if [[ -z "$log_root" ]]; then
    echo "error: no log directory given and none found under logs/ric3_singlecounter/" >&2
    exit 1
  fi
fi

pid_file="$log_root/pids.txt"
if [[ ! -f "$pid_file" ]]; then
  echo "error: no pids.txt in $log_root" >&2
  exit 1
fi

running=0
proved=0
not_proved=0
unknown=0
other=0

while read -r pid name logfile; do
  [[ -z "${pid:-}" ]] && continue
  if kill -0 "$pid" 2>/dev/null; then
    status="RUNNING"
    running=$((running + 1))
  elif grep -q "^rIC3: PROVED" "$logfile" 2>/dev/null; then
    status="PROVED"
    proved=$((proved + 1))
  elif grep -q "UNKNOWN" "$logfile" 2>/dev/null; then
    status="UNKNOWN"
    unknown=$((unknown + 1))
  elif grep -q "^rIC3: NOT PROVED" "$logfile" 2>/dev/null; then
    status="NOT PROVED"
    not_proved=$((not_proved + 1))
  elif [[ ! -s "$logfile" ]]; then
    status="TIMEOUT/KILLED (empty log)"
    other=$((other + 1))
  else
    status="EXITED (no recognized verdict — check log)"
    other=$((other + 1))
  fi
  printf "%-12s %-30s %s\n" "$status" "$name" "$logfile"
done <"$pid_file"

total=$(wc -l <"$pid_file" | tr -d ' ')
echo
echo "=== summary ($total instance(s), log dir: $log_root) ==="
echo "  running:    $running"
echo "  proved:     $proved"
echo "  not proved: $not_proved"
echo "  unknown:    $unknown"
echo "  other:      $other"
