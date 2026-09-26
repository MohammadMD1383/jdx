#!/bin/sh
# bench-compare.sh — reproducible proof for the README claim.
#
# One real task, both ways:
#   "List the API of com.google.gson.Gson, then show ONLY the body of toJson(Object)."
#
# Measures bytes on the wire (wc -c) and estimates tokens as bytes/4.
# Resolves gson-2.14.0 from the local Gradle/Maven caches first, else --fetch.
set -eu

JDX="${JDX:-./app/build/jdx}"
COORD="com.google.code.gson:gson:2.14.0"
GSON_JAR="$(find ~/.gradle/caches ~/.m2 -name 'gson-2.14.0.jar' ! -name '*sources*' 2>/dev/null | head -1 || true)"
GSON_SRC="$(find ~/.gradle/caches ~/.m2 -name 'gson-2.14.0-sources.jar' 2>/dev/null | head -1 || true)"

if [ -z "$GSON_JAR" ]; then
  echo "gson-2.14.0.jar not in local caches; using $JDX --coord (add --fetch to download)" >&2
  JARS_ARGS="--coord $COORD --fetch"
  # byte counts below use live jdx output only; baseline rows are marked n/a
else
  JARS_ARGS="--jars $GSON_JAR --no-jdk"
  echo "binary : $GSON_JAR"
  echo "sources: ${GSON_SRC:-<none>}"
fi

tok() { python3 -c "print(round($1/4))"; }
echo ""
echo "=== jdx path (2 calls) ==="
# shellcheck disable=SC2086
members_bytes=$($JDX members com.google.gson.Gson $JARS_ARGS 2>/dev/null | wc -c)
# shellcheck disable=SC2086
body_bytes=$($JDX body 'com.google.gson.Gson#toJson(Object)' $JARS_ARGS 2>/dev/null | grep -v '^warning\|^next' | wc -c)
total=$((members_bytes + body_bytes))
echo "jdx members : $members_bytes bytes (~$(tok "$members_bytes") tokens)"
echo "jdx body    : $body_bytes bytes (~$(tok "$body_bytes") tokens)"
echo "jdx total   : $total bytes (~$(tok "$total") tokens) in 2 calls"

if [ -n "$GSON_JAR" ]; then
  echo ""
  echo "=== baseline path (5 calls, same task) ==="
  b1=$(unzip -l "$GSON_JAR" 2>/dev/null | grep -i 'Gson.class' | wc -c)
  b2=$(javap -p -cp "$GSON_JAR" com.google.gson.Gson 2>/dev/null | wc -c)
  b3=$(unzip -p "$GSON_SRC" com/google/gson/Gson.java 2>/dev/null | head -200 | wc -c)
  b4=$(unzip -p "$GSON_SRC" com/google/gson/Gson.java 2>/dev/null | grep -n 'toJson' | wc -c)
  b5=$(unzip -p "$GSON_SRC" com/google/gson/Gson.java 2>/dev/null | sed -n '542,547p' | wc -c)
  btotal=$((b1 + b2 + b3 + b4 + b5))
  full=$(unzip -p "$GSON_SRC" com/google/gson/Gson.java 2>/dev/null | wc -c)
  opcodes=$(javap -c -p -cp "$GSON_JAR" com.google.gson.Gson 2>/dev/null | wc -c)
  echo "1 unzip -l | grep          : $b1 bytes"
  echo "2 javap -p (erased sigs)   : $b2 bytes (~$(tok "$b2") tokens)"
  echo "3 unzip -p | head -200     : $b3 bytes (~$(tok "$b3") tokens) -- MISSES method at line 542"
  echo "4 unzip -p | grep -n toJson: $b4 bytes"
  echo "5 unzip -p | sed -n 542,547: $b5 bytes"
  echo "baseline total             : $btotal bytes (~$(tok "$btotal") tokens) in 5 calls"
  echo ""
  echo "Reference sizes (what naive agents actually pull into context):"
  echo "full Gson.java ($full bytes, ~$(tok "$full") tokens) vs jdx body ($body_bytes bytes): $((full / body_bytes))x smaller"
  echo "javap -c ($opcodes bytes, ~$(tok "$opcodes") tokens) vs jdx body ($body_bytes bytes): $((opcodes / body_bytes))x smaller"
  echo "NOTE: head -200 misses the answer (method is at line 542 of 1265); javap erases generics."
fi
