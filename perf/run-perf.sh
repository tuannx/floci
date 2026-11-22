#!/usr/bin/env bash
# Floci perf harness — reruns the deterministic hot-path benchmarks and
# compares median ns/op against perf/baseline.json.
#
# Verdict thresholds (per benchmark, on median ns/op delta vs baseline):
#   regression > 25%  -> block
#   regression > 10%  -> warn
#   otherwise         -> pass (improvements are reported as negative delta)
#
# No Quarkus boot and no full Maven build: the harness compiles the actual
# repo sources of the measured classes with javac and runs them directly.
# Requires: a JDK (21+ works for these sources; the full app needs Java 25),
# curl (only on first run, to fetch the pinned jars), python3.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"

if ! command -v java >/dev/null 2>&1; then
  for cand in "$HOME/workspace/tools/jdk-21.0.13.1+2" "$HOME/workspace/tools/jdk-21.0.12.1+1"; do
    if [ -x "$cand/bin/java" ]; then export JAVA_HOME="$cand"; break; fi
  done
  export PATH="${JAVA_HOME:-}/bin:$PATH"
fi
JAVA_BIN="$(command -v java)"; JAVAC_BIN="$(command -v javac)"
echo "java: $($JAVA_BIN -version 2>&1 | head -1)"

mkdir -p lib classes
fetch() { # path-in-repo url
  if [ ! -s "lib/$(basename "$1")" ]; then
    echo "downloading $(basename "$1")"
    curl -sfL -o "lib/$(basename "$1")" "https://repo.maven.apache.org/maven2/$1"
  fi
}
# Versions pinned to the repo: jackson.version=2.21.4 in pom.xml
# (jackson-annotations for the 2.21 line is versioned 2.21).
fetch com/fasterxml/jackson/core/jackson-core/2.21.4/jackson-core-2.21.4.jar
fetch com/fasterxml/jackson/core/jackson-databind/2.21.4/jackson-databind-2.21.4.jar
fetch com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar
fetch com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.21.4/jackson-datatype-jsr310-2.21.4.jar
fetch org/jboss/logging/jboss-logging/3.6.1.Final/jboss-logging-3.6.1.Final.jar

CP="lib/jackson-core-2.21.4.jar:lib/jackson-databind-2.21.4.jar:lib/jackson-annotations-2.21.jar:lib/jackson-datatype-jsr310-2.21.4.jar:lib/jboss-logging-3.6.1.Final.jar"
COMMON="$ROOT/src/main/java/io/github/hectorvent/floci/core/common"

"$JAVAC_BIN" -cp "$CP" -d classes \
  "$COMMON/XmlBuilder.java" \
  "$COMMON/XmlParser.java" \
  "$COMMON/AwsArnUtils.java" \
  "$ROOT/src/main/java/io/github/hectorvent/floci/services/ssm/model/Parameter.java" \
  src/io/quarkus/runtime/annotations/RegisterForReflection.java \
  src/perf/PerfBench.java

"$JAVA_BIN" -cp "classes:$CP" perf.PerfBench | tee results-last.txt

if [ -f baseline.json ]; then
  python3 - <<'PY'
import json, re, sys
base = json.load(open("baseline.json"))["benchmarks"]
cur = {}
for line in open("results-last.txt"):
    m = re.match(r"(\S+) median_ns_per_op=([\d.]+)", line)
    if m: cur[m.group(1)] = float(m.group(2))
worst = "pass"
for name, b in base.items():
    if name not in cur:
        print(f"{name}: MISSING in current run"); worst = "block"; continue
    delta = (cur[name] - b["median_ns_per_op"]) / b["median_ns_per_op"] * 100.0
    tag = "REGRESSION" if delta > 0 else "improvement"
    print(f"{name}: baseline={b['median_ns_per_op']:.1f} ns/op current={cur[name]:.1f} ns/op delta={delta:+.1f}% ({tag})")
    if delta > 25: worst = "block"
    elif delta > 10 and worst == "pass": worst = "warn"
print(f"verdict: {worst}")
PY
fi
