#!/bin/bash
# Headless MnDOT DataExtract (same code as dataextract.jnlp, no GUI).
# Usage: extract.sh <detectors.txt|MN610.csv> <yyyy-mm-dd>[,yyyy-mm-dd...] <HH:MM start> <HH:MM end> <out.csv> [smoothing in 30-s samples, default 1] [datasets, default Speed,Volume]
# A .csv detector list is read from its "name" column; a .txt list has one detector id per line.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
URL=https://data.dot.state.mn.us/datatools/lib
if [ ! -f "$BUILD/classes/us/mn/state/dot/data/extract/HeadlessExtract.class" ]; then
	mkdir -p "$BUILD/lib" "$BUILD/classes"
	for j in datatools-4.5.5.jar javax.json-1.1.4.jar javax.json-api-1.1.4.jar; do
		curl -sSf -o "$BUILD/lib/$j" "$URL/$j"
	done
	# Driver lives in the jar's package, so strip the jar signatures.
	zip -q -d "$BUILD/lib/datatools-4.5.5.jar" 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 2>/dev/null || true
	javac -nowarn -cp "$BUILD/lib/datatools-4.5.5.jar" -d "$BUILD/classes" "$HERE/HeadlessExtract.java" 2>&1 | grep -v '^Note:' || true
fi
DETS="$1"; shift
if [[ "$DETS" == *.csv ]]; then
	TMP="$(mktemp)"
	python3 -c 'import csv,sys
seen=[]
for r in csv.DictReader(open(sys.argv[1],encoding="utf-8-sig")):
    n=r["name"].strip()
    if n and n not in seen: seen.append(n)
print("\n".join(seen))' "$DETS" > "$TMP"
	DETS="$TMP"
fi
java -Djava.awt.headless=true -cp "$BUILD/classes:$BUILD/lib/*" us.mn.state.dot.data.extract.HeadlessExtract "$DETS" "$@"
