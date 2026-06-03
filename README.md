# LaunchDarkly + Datadog dual-ship observability demo

Working Java and Go reference implementations of the LaunchDarkly evaluation hook required to surface flag evaluations as Datadog APM traces and forward them to LaunchDarkly's Telemetry/Monitoring tab via the Datadog Agent's dual-ship feature.

Implements the contract documented at <https://launchdarkly.com/docs/home/observability/datadog-agent#attaching-feature-flag-context-to-traces>.

## How telemetry flows

The apps use the Datadog tracing libraries (Java `dd-java-agent.jar`, Go `dd-trace-go`). They send spans to the Datadog Agent on `localhost:8126`. The Agent dual-ships every trace to Datadog (`DD_API_KEY`) **and** to LaunchDarkly's intake (`DD_APM_ADDITIONAL_ENDPOINTS` + your LD project's client-side ID).

```text
┌─────────────┐     spans      ┌──────────────────┐   dual-ship    ┌─────────────┐
│  Java / Go  │ ─────────────► │   Datadog Agent  │ ─────────────► │   Datadog   │
│  demo apps  │  localhost:8126│   (Docker/local) │                └─────────────┘
└─────────────┘                │                  │ ─────────────► ┌─────────────┐
                               └──────────────────┘                │ LaunchDarkly│
                                                                   └─────────────┘
```

`launchdarkly.project_id` rides along as a host tag on the Agent (`DD_TAGS`) and as an OTel resource attribute on the app (`OTEL_RESOURCE_ATTRIBUTES`, set by `scripts/run.sh`). The Agent uses it to route the trace to the correct LD project.

## The evaluation hook contract

Both `java/src/main/java/com/example/demo/MonitoringHook.java` and `go/monitoring_hook.go` start a child span on every flag evaluation with this shape:

| Field | Value |
|---|---|
| Operation name | `feature_flag.evaluation` |
| Resource name | the flag key, e.g. `bool-flag-core` |
| Span type | `launchdarkly` |

**Required tags** (LD-mandated names — note `feature_flag.contextKeys` is camelCase plural and holds a JSON object mapping context kind to key):

| Tag | Value |
|---|---|
| `feature_flag.key` | the evaluated flag's key |
| `feature_flag.context.id` | `LDContext.getFullyQualifiedKey()` |
| `feature_flag.contextKeys` | JSON like `{"user":"demo-user"}` |
| `feature_flag.provider.name` | `"LaunchDarkly"` |
| `feature_flag.result.value` | string repr of the evaluated value |

The hooks publish each datum twice: once under the canonical `feature_flag.*` names (what the LD docs prescribe and what Datadog APM displays on the span) and once mirrored under `events.feature_flag.*`, which is the path LD's trace index keys on for the filter below. The same `events.feature_flag.*` tags power the trace-derived signals that Guarded Rollouts can use as their monitoring metric.

## Prerequisites

| Tool | Version |
|------|---------|
| JDK | 21+ |
| Maven | 3.9+ |
| Go | 1.25+ |
| Docker | for the local Datadog Agent |

## Setup

```bash
cp .env.example .env
# Edit: LAUNCHDARKLY_SDK_KEY, LAUNCHDARKLY_CLIENT_SIDE_ID, DD_API_KEY

./scripts/run.sh check
```

Create a boolean flag named `bool-flag-core` (or change `FLAG_KEY` in the apps) in the same LaunchDarkly environment.

## Run

```bash
./scripts/run.sh java   # builds and runs the Java demo on :8080
./scripts/run.sh go     # runs the Go demo on :8081
```

The Datadog Agent is started automatically via Docker Compose on first invocation.

## Generate traffic

```bash
curl "http://localhost:8080/hello?user=demo-user"   # Java
curl "http://localhost:8081/hello?user=demo-user"   # Go
```

## Verify

**In Datadog** — open the `feature_flag.evaluation` span on the trace for `GET /hello`. The five `feature_flag.*` tags, resource name (flag key), and span type `launchdarkly` should all be present.

**In LaunchDarkly Telemetry → Traces** — filter on:

```text
events.feature_flag.key=bool-flag-core
```

The flag's **Monitoring → Traces** sub-page is populated from the same index. Once spans matching this filter start flowing, trace-derived metrics (P99/P95/avg latency, HTTP error rate) become available as Guarded Rollout signals when configuring a rollout on the flag.

## Logs

This demo focuses on **traces**. Shipping **logs** to LaunchDarkly also requires the Datadog Agent log-collection pipeline configured for dual-ship (`logs_dd_url` / additional log endpoints). See the [LaunchDarkly Datadog Agent ingestion doc](https://launchdarkly.com/docs/home/observability/datadog-agent) for log configuration.
