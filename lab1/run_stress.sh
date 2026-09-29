#!/usr/bin/env bash
# Стресс-тест согласованности снимков
set -euo pipefail
cd "$(dirname "$0")"

javac -d out src/*.java
mkdir -p results

java ${JAVA_OPTS:-} -cp out StressTest "$@" | tee results/stress.txt
