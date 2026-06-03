package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"

	httptrace "github.com/DataDog/dd-trace-go/contrib/net/http/v2"
	"github.com/DataDog/dd-trace-go/v2/ddtrace/tracer"
	"github.com/joho/godotenv"
	"github.com/launchdarkly/go-sdk-common/v3/ldcontext"
	ld "github.com/launchdarkly/go-server-sdk/v7"
	"github.com/launchdarkly/go-server-sdk/v7/ldhooks"
)

const flagKey = "demo-flag"

func loadEnv() {
	_ = godotenv.Load(
		".env",
		"../.env",
		filepath.Join("..", ".env"),
	)
}

func main() {
	loadEnv()

	sdkKey := os.Getenv("LAUNCHDARKLY_SDK_KEY")
	if sdkKey == "" {
		log.Fatal("set LAUNCHDARKLY_SDK_KEY in .env")
	}

	service := os.Getenv("DD_SERVICE")
	if service == "" {
		service = os.Getenv("DD_SERVICE_GO")
	}
	if service == "" {
		service = "ld-o11y-demo-go"
	}

	tracer.Start(tracer.WithService(service))
	defer tracer.Stop()

	client, err := ld.MakeCustomClient(sdkKey, ld.Config{
		Hooks: []ldhooks.Hook{newMonitoringHook()},
	}, 5*time.Second)
	if err != nil {
		log.Fatalf("ld client: %v", err)
	}
	defer client.Close()

	mux := httptrace.NewServeMux()
	mux.HandleFunc("/hello", func(w http.ResponseWriter, r *http.Request) {
		user := r.URL.Query().Get("user")
		if user == "" {
			user = "demo-user"
		}
		ldCtx := ldcontext.NewBuilder(user).Kind("user").Build()

		value, _, err := client.BoolVariationDetailCtx(r.Context(), flagKey, ldCtx, false)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		fmt.Fprintf(w, "hello %s flag=%s value=%v\n", user, flagKey, value)
	})

	addr := ":8081"
	log.Printf("listening on %s (GET /hello?user=demo-user)", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}
