# Java demo

Plain Java 21 HTTP server on **`:8080`**.

- `DemoApplication.java` — HTTP server + LaunchDarkly client
- `MonitoringHook.java` — LaunchDarkly evaluation hook that emits the `feature_flag.evaluation` Datadog span (see top-level [README](../README.md) for the contract)

## Run

From repo root:

```bash
cp .env.example .env   # if not already
./scripts/run.sh java
```

Requires JDK 21+ and Maven on `PATH`. The Datadog Java tracer is downloaded to `java/lib/` automatically on first run.

## Traffic

```bash
curl "http://localhost:8080/hello?user=demo-user"
```
