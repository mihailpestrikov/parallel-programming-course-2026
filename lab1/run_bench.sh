#!/usr/bin/env bash
# Полный замер пропускной способности (~16 минут)
# Быстрая проверка: JAVA_OPTS="-Dwarmup=1 -Druns=1 -Dsec=1" ./run_bench.sh
set -euo pipefail
cd "$(dirname "$0")"

THREADS="1 2 4 6 8 12"

javac -d out src/*.java
mkdir -p results

{
  sysctl -n machdep.cpu.brand_string
  sysctl hw.ncpu hw.perflevel0.physicalcpu hw.perflevel1.physicalcpu hw.cachelinesize
  java -version 2>&1
} > results/env.txt

{
  echo "impl,threads,median,r1,r2,r3,r4,r5"
  java ${JAVA_OPTS:-} -cp out Bench single 1
  for impl in empty sync striped threadlocal double; do
    java ${JAVA_OPTS:-} -cp out Bench "$impl" $THREADS
  done
} | tee results/bench.csv
