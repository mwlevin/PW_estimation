#!/bin/bash
# Leave-one-out validation: withholds each interior mainline station of both directions in turn (in parallel) and summarizes
# the errors. The first and last stations are not withheld: they supply the flows at the ends of the corridor.
# Usage: tools/validation/validate.sh <detector data CSV> <duration (s)> <output folder> [java options, e.g. "-Dc0=15 -Dtau=30"]
# Environment variables:
#   CORRIDOR=<folder>  a corridor made by tools/corridors/build_corridor.py (e.g. data/I94); default MN-610
#   STEP=<n>           withhold only every n-th interior station of each direction (for long corridors); default 1
#   CLASSES=<folder>   use already compiled classes instead of compiling src/main/java
#   JOBS=<n>           number of runs at a time; default the number of processors
# Run from the PW_estimation folder.
set -euo pipefail
DATA="$1"; DURATION="$2"; OUT="$3"; OPTS="${4:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MATH="$HOME/.m2/repository/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar"
mkdir -p "$OUT"
if [ -n "${CLASSES:-}" ]; then
	BUILD="$CLASSES"
else
	BUILD="$OUT/classes"
	mkdir -p "$BUILD"
	javac -nowarn -cp "$MATH" -d "$BUILD" $(find src/main/java -name '*.java') 2>&1 | grep -v '^Note:' || true
fi
CP="$BUILD:$MATH"
JOBS="${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || nproc)}"
STEP="${STEP:-1}"
DIRS="EB WB"
if [ -n "${CORRIDOR:-}" ]; then
	OPTS="$OPTS -Dcorridor=$CORRIDOR"
	DIRS=$(grep '^labels=' "$CORRIDOR/corridor.properties" | cut -d= -f2 | tr ',' ' ')
fi
for DIR in $DIRS; do
	N=$(java $OPTS -cp "$CP" umn.pw_estimation.Validation "$DATA" 60 $DIR list 2>/dev/null | grep -cE '^[0-9]+ cell ')
	for ((s = 1; s < N - 1; s += STEP)); do echo "$DIR $s"; done
done | xargs -P "$JOBS" -n 2 sh -c 'java -XX:+UseSerialGC -XX:TieredStopAtLevel=4 -Djava.awt.headless=true '"$OPTS"' -cp "'"$CP"'" umn.pw_estimation.Validation "'"$DATA"'" '"$DURATION"' $0 $1 3 "'"$OUT"'/$0_$1.csv" > /dev/null 2>> "'"$OUT"'/log.txt"'
python3 "$HERE/summarize.py" "$OUT"
