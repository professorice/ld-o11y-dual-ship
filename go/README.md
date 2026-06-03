# Go demo

HTTP server on **`:8081`**.

- `main.go` — HTTP server + LaunchDarkly client
- `monitoring_hook.go` — LaunchDarkly evaluation hook that emits the `feature_flag.evaluation` Datadog span (see top-level [README](../README.md) for the contract)

## Run

From repo root:

```bash
cp .env.example .env   # if not already
./scripts/run.sh go
```

Or directly from `go/` (loads `.env` via godotenv):

```bash
go run .
```

## Traffic

```bash
curl "http://localhost:8081/hello?user=demo-user"
```
