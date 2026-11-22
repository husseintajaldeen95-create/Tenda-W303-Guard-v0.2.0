#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/tenda-w303-guard-tests"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -d "$OUT" \
  "$ROOT/app/src/main/java/com/tenda/w303guard/MacAddress.java" \
  "$ROOT/app/src/main/java/com/tenda/w303guard/IpReservationValidator.java" \
  "$ROOT/app/src/main/java/com/tenda/w303guard/MacScheduleLogic.java" \
  "$ROOT/tests/java/IpReservationValidatorTest.java" \
  "$ROOT/tests/java/MacScheduleLogicTest.java"
java -cp "$OUT" com.tenda.w303guard.IpReservationValidatorTest
java -cp "$OUT" com.tenda.w303guard.MacScheduleLogicTest
