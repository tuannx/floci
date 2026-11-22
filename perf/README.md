# Floci perf harness

Deterministic timing benchmarks for Floci hot paths that do **not** require
booting the Quarkus application. The repo has no JMH setup; this harness is a
plain `javac`/`java` timing loop with fixed inputs, fixed warmup
(20,000 iters) and fixed measurement (5 rounds × 50,000 iters, median ns/op).

## Benchmarks

| Name | Hot path |
|---|---|
| `xml_builder_build` | `core.common.XmlBuilder` building an SQS `ReceiveMessageResponse` XML doc |
| `xml_parser_extract` | `core.common.XmlParser` (`extractAll` + `extractPairs`, StAX) on a fixed XML request |
| `arn_parse` | `core.common.AwsArnUtils.parse` + `arnToQueueUrl` |
| `jackson_ssm_parameter_roundtrip` | Jackson serialize+deserialize of SSM `model.Parameter` |

The harness compiles the actual repo sources of those classes. One
compile-only stub is included (`io.quarkus.runtime.annotations.RegisterForReflection`)
because the real annotation ships with Quarkus, which the harness does not boot.

## Run

```bash
perf/run-perf.sh
```

First run downloads the pinned jars (Jackson 2.21.4 per `pom.xml`,
JBoss Logging 3.6.1.Final) into `perf/lib/` (git-ignored). The script prints
per-benchmark delta % vs `perf/baseline.json` and a verdict:
regression >10% = `warn`, >25% = `block`.

## Caveats

- Measured 2026-11-21 on a shared VM (~1.5 effective CPU). Run-to-run spread
  was ~2–3× on the smallest benchmarks; see `observed_range_ns_per_op` in
  `baseline.json`. Deltas under ~25% are inconclusive on this machine.
- The full app targets Java 25 (`maven.compiler.release=25`); this VM only
  has JDK 21, so no full Maven build was run. The measured sources compile
  and run unchanged under JDK 21.
