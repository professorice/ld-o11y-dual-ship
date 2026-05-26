# Java demo

Plain Java 21 HTTP server on **8080** with `MonitoringHook.java` (no Spring Boot).

## Run

From repo root:

```bash
cp .env.example .env   # if you have not already
./scripts/run.sh java
```

Requires JDK 21+ and Maven on `PATH`. The Datadog Java tracer is downloaded to `java/lib/` automatically if missing.

## Traffic

```bash
curl "http://localhost:8080/hello?user=demo-user"
```
