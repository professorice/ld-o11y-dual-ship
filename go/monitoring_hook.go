package main

import (
	"context"
	"encoding/json"

	"github.com/DataDog/dd-trace-go/v2/ddtrace/tracer"
	"github.com/launchdarkly/go-sdk-common/v3/ldreason"
	"github.com/launchdarkly/go-server-sdk/v7/ldhooks"
)

// monitoringHook attaches feature-flag-evaluation metadata to the active Datadog trace in
// the shape LaunchDarkly's Datadog Agent ingest expects, per
// https://launchdarkly.com/docs/home/observability/datadog-agent#attaching-feature-flag-context-to-traces
//
// On every evaluation it starts a child span named `feature_flag.evaluation` with span type
// `launchdarkly` and resource name = the flag key, then publishes each datum twice as
// span-level tags: once under the canonical `feature_flag.*` names (what the LD docs
// prescribe and what Datadog APM displays on the span) and once mirrored under
// `events.feature_flag.*`, which is the path LD's trace index keys on for the filter
//
//	events.feature_flag.key=bool-flag-core
//
// in LD Telemetry → Traces and on the flag's Monitoring → Traces sub-page. The same
// `events.feature_flag.*` tags power the trace-derived signals (latency, error rate) that
// Guarded Rollouts can monitor. The data is set as span-level tags (not span events) because
// LD's intake indexes span tags, not the nested attributes of a span event.
//
// Required tags (use these exact names — `feature_flag.contextKeys` is camelCase and a JSON
// object mapping context kind → key):
//
//   - feature_flag.key            : the evaluated flag's key
//   - feature_flag.context.id     : LDContext.FullyQualifiedKey()
//   - feature_flag.contextKeys    : JSON like {"user":"demo-user"}
//   - feature_flag.provider.name  : "LaunchDarkly"
//   - feature_flag.result.value   : string repr of the evaluated value
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
	flagKey := seriesContext.FlagKey()
	ldCtx := seriesContext.Context()

	contextKeys := map[string]string{}
	for i := 0; i < ldCtx.IndividualContextCount(); i++ {
		if c := ldCtx.IndividualContextByIndex(i); c.IsDefined() {
			contextKeys[string(c.Kind())] = c.Key()
		}
	}
	if len(contextKeys) == 0 && ldCtx.IsDefined() {
		contextKeys[string(ldCtx.Kind())] = ldCtx.Key()
	}
	contextKeysJSON, _ := json.Marshal(contextKeys)

	span, _ := tracer.StartSpanFromContext(ctx, "feature_flag.evaluation",
		tracer.ResourceName(flagKey),
		tracer.SpanType("launchdarkly"),
	)

	ctxKey := ldCtx.FullyQualifiedKey()
	span.SetTag("feature_flag.key", flagKey)
	span.SetTag("feature_flag.context.id", ctxKey)
	span.SetTag("feature_flag.contextKeys", string(contextKeysJSON))
	span.SetTag("feature_flag.provider.name", "LaunchDarkly")

	// Also publish the same data as flat tags under the `events.feature_flag.*` namespace.
	// This is the path LaunchDarkly's trace search indexes for the filter
	// `events.feature_flag.key=<flag-key>` in Telemetry → Traces and on the flag's
	// Monitoring → Traces sub-page; it is also what powers Guarded Rollout signals derived
	// from trace data. These must be span-level tags (not span events) for LD's intake to
	// index them.
	span.SetTag("events.feature_flag.key", flagKey)
	span.SetTag("events.feature_flag.context.id", ctxKey)
	span.SetTag("events.feature_flag.contextKeys", string(contextKeysJSON))
	span.SetTag("events.feature_flag.provider.name", "LaunchDarkly")

	return ldhooks.NewEvaluationSeriesBuilder(data).Set("monitoringSpan", span).Build(), nil
}

func (h monitoringHook) AfterEvaluation(
	_ context.Context,
	_ ldhooks.EvaluationSeriesContext,
	data ldhooks.EvaluationSeriesData,
	detail ldreason.EvaluationDetail,
) (ldhooks.EvaluationSeriesData, error) {
	v, ok := data.Get("monitoringSpan")
	if !ok {
		return data, nil
	}
	span, ok := v.(*tracer.Span)
	if !ok {
		return data, nil
	}

	resultValue := detail.Value.JSONString()
	span.SetTag("feature_flag.result.value", resultValue)
	span.SetTag("events.feature_flag.result.value", resultValue)
	span.Finish()
	return data, nil
}
