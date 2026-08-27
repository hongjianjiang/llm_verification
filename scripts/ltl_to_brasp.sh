#!/usr/bin/env bash
# Round-trip every .ltl example back into a B-RASP program:
#   examples/ltl/<stem>.ltl  --brasp-->  examples/brasp/<stem>.brasp
#
#   scripts/ltl_to_brasp.sh                          # every examples/ltl/*.ltl
#   scripts/ltl_to_brasp.sh examples/ltl/y_depth__*.ltl   # just these
#   TIMEOUT=300 scripts/ltl_to_brasp.sh              # longer budget
#
# The destination is NOT ours to choose: `--brasp` auto-saves to
# `examples/brasp/<input stem>.brasp` itself (Translator.braspOutputPath), so
# this script only drives the jar and reports what moved. That means it
# overwrites an existing .brasp of the same stem — including a hand-written
# one. The round-trip is idempotent on the current corpus (regenerating leaves
# every file byte-identical), so anything reported as `updated` is a real
# change worth looking at in `git diff` before committing.
#
# Env: JAR, TIMEOUT (seconds).
set -uo pipefail

JAR=${JAR:-target/scala-3.5.1/brasp-verification.jar}
TIMEOUT=${TIMEOUT:-120}

# GNU timeout is `gtimeout` where coreutils is installed under its g- prefix.
if command -v timeout >/dev/null 2>&1; then TIMEOUT_BIN=timeout
elif command -v gtimeout >/dev/null 2>&1; then TIMEOUT_BIN=gtimeout
else echo "ltl_to_brasp.sh: need GNU timeout (brew install coreutils)" >&2; exit 1
fi

[ -f "$JAR" ] || { echo "ltl_to_brasp.sh: no jar at $JAR — run 'sbt assembly' first" >&2; exit 1; }

files=("$@")
if [ ${#files[@]} -eq 0 ]; then files=(examples/ltl/*.ltl); fi

# Hash a file, or print nothing if it isn't there yet — used to tell a fresh
# translation apart from one that rewrote an existing program.
digest() { [ -f "$1" ] && shasum -a 256 "$1" | cut -d' ' -f1; }

created=0; updated=0; unchanged=0; failed=0; skipped=0
for f in "${files[@]}"; do
  # The glob is expanded once, up front; a file can disappear before the
  # loop reaches it if something else is editing the corpus concurrently.
  [ -f "$f" ] || { echo "ltl_to_brasp.sh: skipping missing $f" >&2; skipped=$((skipped+1)); continue; }
  stem=$(basename "$f" .ltl)
  out="examples/brasp/$stem.brasp"
  before=$(digest "$out")

  err=$("$TIMEOUT_BIN" "$TIMEOUT" java -jar "$JAR" "$f" --brasp 2>&1 >/dev/null)
  code=$?

  if [ $code -eq 124 ]; then
    status=TIMEOUT; failed=$((failed+1))
  elif [ $code -ne 0 ]; then
    status=FAILED; failed=$((failed+1))
    # Keep the translator's own diagnostic — it names the construct that
    # has no B-RASP form, which the exit code alone doesn't.
    [ -n "$err" ] && echo "  ${err#translator: }" >&2
  else
    after=$(digest "$out")
    if   [ -z "$before" ];            then status=created;   created=$((created+1))
    elif [ "$before" != "$after" ];   then status=updated;   updated=$((updated+1))
    else                                   status=unchanged; unchanged=$((unchanged+1))
    fi
  fi
  printf '%-44s %s\n' "$stem" "$status" >&2
done

echo >&2
echo "$created created, $updated updated, $unchanged unchanged, $failed failed, $skipped vanished mid-run" >&2
[ "$failed" -eq 0 ]
