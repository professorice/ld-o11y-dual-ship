package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"

	httptrace "github.com/DataDog/dd-trace-go/contrib/net/http/v2"
	"github.com/DataDog/dd-trace-go/v2/ddtrace/tracer"
	"github.com/launchdarkly/go-sdk-common/v3/ldcontext"
	"github.com/launchdarkly/go-sdk-common/v3/ldreason"
	ld "github.com/launchdarkly/go-server-sdk/v7"
	"github.com/launchdarkly/go-server-sdk/v7/ldhooks"
	"go.opentelemetry.io/otel"
	"github.com/joho/godotenv"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

const flagKey = "demo-flag"

type monitoringHook struct {
	ldhooks.Unimplemented
	metadata ldhooks.Metadata
}

func newMonitoringHook() monitoringHook {
	return monitoringHook{metadata: ldhooks.NewMetadata("LaunchDarkly Monitoring Hook")}
}

func (h monitoringHook) Metadata() ldhooks.Metadata {
	return h.metadata
}

func (h monitoringHook) BeforeEvaluation(
	ctx context.Context,
	seriesContext ldhooks.EvaluationSeriesContext,
	data ldhooks.EvaluationSeriesData,
) (ldhooks.EvaluationSeriesData, error) {
	otelTracer := otel.Tracer("launchdarkly-client")
	_, span := otelTracer.Start(ctx, seriesContext.Method())
	return ldhooks.NewEvaluationSeriesBuilder(data).Set("monitoringSpan", span).Build(), nil
}

func (h monitoringHook) AfterEvaluation(
	ctx context.Context,
	seriesContext ldhooks.EvaluationSeriesContext,
	data ldhooks.EvaluationSeriesData,
	detail ldreason.EvaluationDetail,
) (ldhooks.EvaluationSeriesData, error) {
	if v, ok := data.Get("monitoringSpan"); ok {
		if span, ok := v.(trace.Span); ok {
			span.AddEvent("feature_flag", trace.WithAttributes(
				attribute.String("feature_flag.key", seriesContext.FlagKey()),
				attribute.String("feature_flag.provider.name", "LaunchDarkly"),
				attribute.String("feature_flag.context.id", seriesContext.Context().FullyQualifiedKey()),
				attribute.String("feature_flag.result.value", detail.Value.JSONString()),
			))
			span.End()
		}
	}
	return data, nil
}

func loadEnv() {
	// Repo root .env when running from go/, or local .env
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
