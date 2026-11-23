#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p backend/out
javac --release 17 -d backend/out backend/src/restaurant/*.java
exec java -cp backend/out restaurant.Main
