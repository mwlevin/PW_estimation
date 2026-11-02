#!/bin/bash
# Runs a corridor's model on one day's AM and PM peaks: the estimate with all detectors (and its space-time heatmaps), and
# leave-one-out validation.
# Usage: tools/corridors/test_corridor.sh <corridor folder> <yyyy-mm-dd> <output folder> [java options]
# Periods: 6:30-9:30 AM and 3:30-6:30 PM, so the first 30 minutes (and the 5-minute warm-up excluded from the errors)
# come before the peaks used to choose the day (7-9 AM and 4-6 PM; see find_congestion.py).
# Environment variables STEP, CLASSES and JOBS are passed to tools/validation/validate.sh.
set -euo pipefail
C="$1"; DAY="$2"; OUT="$3"; OPTS="${4:-}"
MATH="$HOME/.m2/repository/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar"
mkdir -p "$OUT"
if [ -z "${CLASSES:-}" ]; then
	CLASSES="$OUT/classes"
	mkdir -p "$CLASSES"
	javac -nowarn -cp "$MATH" -d "$CLASSES" $(find src/main/java -name '*.java') 2>&1 | grep -v '^Note:' || true
fi
export CLASSES
NAME=$(grep '^name=' "$C/corridor.properties" | cut -d= -f2)
DIRS=$(grep '^labels=' "$C/corridor.properties" | cut -d= -f2 | tr ',' ' ')
for P in "AM 06:30 09:30 6:30" "PM 15:30 18:30 15:30"; do
	set -- $P
	DATA="$OUT/data_$1.csv"
	python3 tools/corridors/slice.py "$C/daily/$DAY.csv" $2 $3 "$DATA"
	mkdir -p "$OUT/all_$1"
	for D in $DIRS; do
		java -XX:+UseSerialGC -Djava.awt.headless=true $OPTS -Dcorridor="$C" -Dcells_out="$OUT/all_$1/${D}_cells.csv" \
			-cp "$CLASSES:$MATH" umn.pw_estimation.Validation "$DATA" 10800 $D none 3 "$OUT/all_$1/$D.csv" > "$OUT/all_$1/$D.log" 2>&1 &
	done
	wait
	for D in $DIRS; do
		python3 tools/validation/heatmap.py "$OUT/all_$1/${D}_cells.csv" "$OUT/all_$1/$D.csv" "$OUT/${D}_$1_density.png" \
			--start $4 --title "$NAME $D, $DAY $1: estimated density, all detectors used"
		python3 tools/validation/heatmap.py "$OUT/all_$1/${D}_cells.csv" "$OUT/all_$1/$D.csv" "$OUT/${D}_$1_speed.png" \
			--start $4 --value speed --title "$NAME $D, $DAY $1: estimated speed, all detectors used"
	done
	CORRIDOR="$C" tools/validation/validate.sh "$DATA" 10800 "$OUT/loo_$1" "$OPTS" > "$OUT/loo_$1.txt"
done
