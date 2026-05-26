# Go demo

HTTP server on **8081** with the same hook pattern in `main.go`.

## Run

From repo root:

```bash
cp .env.example .env   # if you have not already
./scripts/run.sh go
```

Or from `go/` (loads `.env` via godotenv):

```bash
go run .
```

## Traffic

```bash
curl "http://localhost:8081/hello?user=demo-user"
```
