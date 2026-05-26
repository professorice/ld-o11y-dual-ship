# LaunchDarkly Monitoring trace demo (Datadog dual-ship)

Java and Go apps that emit a `feature_flag` **span event** on an OTel child span — the shape Flag Monitoring → Traces indexes.

## How telemetry flows

This repo includes **tracing libraries** (Java `dd-java-agent.jar`, Go `dd-trace-go`). Those are **not** the Datadog Agent.

```text
┌─────────────┐     spans      ┌──────────────────┐   dual-ship    ┌────────────┐
│  Java / Go  │ ─────────────► │ Datadog Agent    │ ─────────────► │ Datadog    │
│  demo apps  │  localhost:8126│ (Docker/local)   │                └────────────┘
└─────────────┘                │                  │ ─────────────► ┌────────────┐
                               └──────────────────┘                │ LaunchDarkly│
                                                                   └────────────┘
```

Per [LaunchDarkly’s Datadog Agent docs](https://launchdarkly.com/docs/home/observability/datadog-agent):

1. Apps instrument requests and flag evaluations (Monitoring hooks in this repo).
2. Tracers send spans to the **Datadog Agent** on `localhost:8126`.
3. The **Agent** dual-ships to your Datadog account (`DD_API_KEY`) and LaunchDarkly (`DD_APM_ADDITIONAL_ENDPOINTS` + client-side ID).

The `DD_APM_ADDITIONAL_ENDPOINTS` value in `.env` is applied to the **Agent** (via `docker-compose.yml`), not to the JVM/Go process. The Agent also gets host tag `launchdarkly.project_id` via `DD_TAGS`; apps set the same via `OTEL_RESOURCE_ATTRIBUTES` (see `scripts/run.sh`).

## Prerequisites

| Tool | Version |
|------|---------|
| JDK | 21+ |
| Maven | 3.9+ |
| Go | 1.25+ |
| Docker | for local Datadog Agent (recommended) |
| curl | for downloading the Java tracer |

## Setup

```bash
cp .env.example .env
# Edit: LAUNCHDARKLY_SDK_KEY, LAUNCHDARKLY_CLIENT_SIDE_ID, DD_API_KEY

chmod +x run.sh
./run.sh check
```

Create boolean flag **`demo-flag`** in the same LaunchDarkly environment.

## Run

```bash
./run.sh java   # port 8080
./run.sh go     # port 8081
```

The first run starts the Datadog Agent in Docker automatically if nothing is listening on `localhost:8126`.

## Generate traffic

```bash
curl "http://localhost:8080/hello?user=demo-user"   # Java
curl "http://localhost:8081/hello?user=demo-user"   # Go
```

Wait a few minutes for data to appear.

## Verify in Datadog

On the OTel child span (`LDClient.boolVariationDetail`):

- Event **`feature_flag`**
- `feature_flag.key=demo-flag`
- `feature_flag.result.value` set

## Verify in LaunchDarkly

Telemetry → Traces (or Flag → Monitoring → Traces):

```text
any_span(feature_flag.key=demo-flag)
```

## Logs

This demo focuses on **traces**. Shipping **logs** to LaunchDarkly also requires the Datadog Agent log collection pipeline configured for dual-ship (`logs_dd_url` / additional log endpoints). See the [Datadog Agent ingestion](https://launchdarkly.com/docs/home/observability/datadog-agent) doc for log configuration.
