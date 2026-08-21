#!/bin/sh
set -eu

REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$REPO_DIR"
BUILD_DIR="${TMPDIR:-/tmp}/shipment-job-monitor-classes"
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" $(find src/main/java src/test/java -name '*.java' -print)
java -cp "$BUILD_DIR" dev.infrai.logistics.LogisticsJobMonitorTest
