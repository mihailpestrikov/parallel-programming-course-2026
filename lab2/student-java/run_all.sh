#!/bin/sh
set -eu
cd "$(dirname "$0")"

javac -d out src/lab2/*.java
mkdir -p results

run() {
    echo "\$ $*"
    "$@"
    echo
}

explore() {
    out=$(./lab2 "$@")
    printf '$ ./lab2 %s\n%s\n\n' "$*" "$out"
    trace=$(printf '%s\n' "$out" | sed -n 's/^first failing: \([A-Z]*\) .*/\1/p')
    if [ -n "$trace" ]; then run ./lab2 "$1" replay "$trace" log; fi
}

{
    sysctl -n machdep.cpu.brand_string
    sysctl hw.ncpu hw.perflevel0.physicalcpu hw.perflevel1.physicalcpu
    sw_vers
    uname -sr
    java -version 2>&1
} > results/env.txt || true

echo "== этап 0: стресс-тест"
{
    for args in "none v1" "yield v1" "none v2"; do
        for i in 1 2 3; do java -cp out lab2.Stress 10000 $args; done
    done
} | tee results/stage0.txt

echo "== этапы 1-2"
{
    run ./lab2 withdraw-v0 first log
    run ./lab2 withdraw-v1 first log
    run ./lab2 transfer first
    run ./lab2 transfer replay AB log
} | tee results/stage1-2.txt

echo "== этап 3: random"
{
    out=$(./lab2 withdraw-v1 randoms 1000)
    printf '$ ./lab2 withdraw-v1 randoms 1000\n%s\n\n' "$out"
    trace=$(printf '%s\n' "$out" | sed -n 's/^first failing seed [0-9]*: \([A-Z]*\) .*/\1/p')
    run ./lab2 withdraw-v1 replay "$trace" log
} | tee results/stage3.txt

echo "== этап 4: dfs"
{
    for t in withdraw-v1 withdraw-v2 withdraw-v0 transfer; do explore "$t" dfs; done
} | tee results/stage4.txt

echo "== этап 5: bounded"
{
    for k in 0 1 2; do explore withdraw-v1 bounded "$k"; done
    for k in 0 1; do explore transfer bounded "$k"; done
} | tee results/stage5.txt

echo "== этап 6: коллектор"
{
    explore collector-nostep3-1x1 dfs
    for n in 2 3 4; do run ./lab2 "collector-nostep3-1x$n" dfs; done
    for k in 0 1 2 3; do run ./lab2 collector-nostep3-2x1 bounded "$k"; done
    for v in plain acqrel ok; do run ./lab2 "collector-$v-1x1" dfs; done
    run ./lab2 collector-ok-2x1 bounded 2    # контрольная точка
} | tee results/stage6.txt

echo "== этап 7: store buffering"
{
    run java -cp out lab2.MiniStress acqrel 200000 100
    run java -cp out lab2.MiniStress acqrel 200 100000
    for i in 1 2 3; do
        for m in plain acqrel volatile; do run java -cp out lab2.MiniStress "$m" 2000 10000; done
        run java -cp out lab2.MiniStress naive 200000 1
    done
    run java -cp out lab2.MiniStress plain 200000 100
    run java -cp out lab2.MiniStress plain 200 100000
} | tee results/stage7.txt
