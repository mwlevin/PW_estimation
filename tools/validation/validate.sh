#!/bin/bash
# Leave-one-out validation: withholds each mainline station of both directions in turn (in parallel) and summarizes the errors.
# Usage: tools/validation/validate.sh <detector data CSV> <duration (s)> <output folder> [java options, e.g. "-Dc0=15 -Dtau=30"]
# Run from the PW_estimation folder.
set -euo pipefail
DATA="$1"; DURATION="$2"; OUT="$3"; OPTS="${4:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MATH="$HOME/.m2/repository/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar"
BUILD="$OUT/classes"
mkdir -p "$BUILD"
javac -nowarn -cp "$MATH" -d "$BUILD" $(find src/main/java -name '*.java') 2>&1 | grep -v '^Note:' || true
CP="$BUILD:$MATH"
JOBS="${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || nproc)}"
for DIR in EB WB; do
	N=$(java -cp "$CP" umn.pw_estimation.Validation "$DATA" 60 $DIR list 2>/dev/null | grep -c ' cell ')
	for ((s = 0; s < N; s++)); do echo "$DIR $s"; done
done | xargs -P "$JOBS" -n 2 sh -c 'java -XX:+UseSerialGC -XX:TieredStopAtLevel=4 -Djava.awt.headless=true '"$OPTS"' -cp "'"$CP"'" umn.pw_estimation.Validation "'"$DATA"'" '"$DURATION"' $0 $1 3 "'"$OUT"'/$0_$1.csv" > /dev/null 2>> "'"$OUT"'/log.txt"'
python3 "$HERE/summarize.py" "$OUT"
