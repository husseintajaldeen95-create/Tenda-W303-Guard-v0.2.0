#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/tenda-ip-reservation-tests"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -d "$OUT" \
  "$ROOT/app/src/main/java/com/tenda/w303guard/MacAddress.java" \
  "$ROOT/app/src/main/java/com/tenda/w303guard/IpReservationValidator.java" \
  "$ROOT/tests/java/IpReservationValidatorTest.java"
java -cp "$OUT" com.tenda.w303guard.IpReservationValidatorTest
