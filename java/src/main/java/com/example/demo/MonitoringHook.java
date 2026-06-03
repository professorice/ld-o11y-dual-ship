package com.example.demo;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.server.integrations.Hook;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;

/**
 * Attaches feature-flag-evaluation metadata to the active Datadog trace in the shape
 * LaunchDarkly's Datadog Agent ingest expects, per
 * https://launchdarkly.com/docs/home/observability/datadog-agent#attaching-feature-flag-context-to-traces
 *
 * <p>On every evaluation starts a child OTel span named {@code feature_flag.evaluation}
 * parented to the active HTTP span and sets all metadata as span-level attributes.
 * dd-trace-java's OTel bridge maps {@code operation.name}, {@code resource.name}, and
 * {@code span.type} OTel attributes to the corresponding Datadog fields on the wire.
 *
 * <p>Filter in LaunchDarkly Telemetry → Traces:
 *
 * <pre>events.feature_flag.key=bool-flag-core</pre>
 *
 * <p>The hook publishes each datum twice as span-level attributes: once under the canonical
 * {@code feature_flag.*} names (what the LaunchDarkly docs prescribe and what Datadog APM
 * displays on the span) and once mirrored under {@code events.feature_flag.*}, which is the
 * path LD's trace index keys on for the filter above. The same {@code events.feature_flag.*}
 * tags power the trace-derived signals (latency, error rate) that Guarded Rollouts can
 * monitor. The data is set as span-level attributes (not nested inside a span event) because
 * LD's intake indexes span attributes, not span-event attributes.
 *
 * <p>Required tags (use these exact names — {@code feature_flag.contextKeys} is camelCase
 * plural and holds a JSON object mapping context kind to key):
 *
 * <ul>
 *   <li>{@code feature_flag.key} — the evaluated flag's key
 *   <li>{@code feature_flag.context.id} — {@link LDContext#getFullyQualifiedKey()}
 *   <li>{@code feature_flag.contextKeys} — JSON like {@code {"user":"demo-user"}}
 *   <li>{@code feature_flag.provider.name} — {@code "LaunchDarkly"}
 *   <li>{@code feature_flag.result.value} — string repr of the evaluated value
 * </ul>
 */
public final class MonitoringHook extends Hook {

  private static final String HOOK_NAME = "LaunchDarkly Monitoring Hook";
  private static final String SPAN_KEY = "monitoringSpan";
  private static final String SPAN_NAME = "feature_flag.evaluation";
  private static final String TRACER_NAME = "launchdarkly-client";

  public MonitoringHook() {
    super(HOOK_NAME);
  }

  @Override
  public Map<String, Object> beforeEvaluation(
      EvaluationSeriesContext seriesContext,
      Map<String, Object> seriesData) {

    String flagKey = seriesContext.flagKey;
    LDContext ctx = seriesContext.context;

    Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);
    Span span = tracer.spanBuilder(SPAN_NAME)
        .setParent(Context.current().with(Span.current()))
        .startSpan();

    // dd-trace-java's OTel bridge maps these to DD operation/resource/type on the wire.
    // Without `operation.name`, dd-trace-java synthesises the DD name from SpanKind=INTERNAL
    // ("internal") and the OTel span name is lost.
    span.setAttribute("operation.name", SPAN_NAME);
    span.setAttribute("resource.name", flagKey);
    span.setAttribute("span.type", "launchdarkly");

    String ctxKey = ctx.getFullyQualifiedKey();
    String contextKeysJson = buildContextKeysJson(ctx);
    span.setAttribute("feature_flag.key", flagKey);
    span.setAttribute("feature_flag.context.id", ctxKey);
    span.setAttribute("feature_flag.contextKeys", contextKeysJson);
    span.setAttribute("feature_flag.provider.name", "LaunchDarkly");

    // Also publish the same data as flat tags under the `events.feature_flag.*` namespace.
    // This is the path LaunchDarkly's trace search indexes for the filter
    // `events.feature_flag.key=<flag-key>` in Telemetry → Traces and on the flag's
    // Monitoring → Traces sub-page; it is also what powers Guarded Rollout signals derived
    // from trace data. These must be span-level attributes (not nested inside a span event)
    // for LD's intake to index them.
    span.setAttribute("events.feature_flag.key", flagKey);
    span.setAttribute("events.feature_flag.context.id", ctxKey);
    span.setAttribute("events.feature_flag.contextKeys", contextKeysJson);
    span.setAttribute("events.feature_flag.provider.name", "LaunchDarkly");

    Map<String, Object> next = new HashMap<>(seriesData);
    next.put(SPAN_KEY, span);
    return next;
  }

  @Override
  public Map<String, Object> afterEvaluation(
      EvaluationSeriesContext seriesContext,
      Map<String, Object> seriesData,
      EvaluationDetail<LDValue> detail) {

    Object stored = seriesData.get(SPAN_KEY);
    if (!(stored instanceof Span span)) {
      return seriesData;
    }
    String resultValue = detail.getValue().toJsonString();
    span.setAttribute("feature_flag.result.value", resultValue);
    span.setAttribute("events.feature_flag.result.value", resultValue);
    span.end();
    return seriesData;
  }

  /**
   * Builds the {@code feature_flag.contextKeys} JSON: an object mapping each context kind to
   * its key. Single context → {@code {"user":"demo-user"}}; multi-context → one entry per kind.
   */
  private static String buildContextKeysJson(LDContext ctx) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    if (ctx.isMultiple()) {
      for (int i = 0; i < ctx.getIndividualContextCount(); i++) {
        LDContext c = ctx.getIndividualContext(i);
        if (c == null) {
          continue;
        }
        if (!first) {
          sb.append(",");
        }
        first = false;
        appendJsonString(sb, c.getKind().toString());
        sb.append(":");
        appendJsonString(sb, c.getKey());
      }
    } else {
      appendJsonString(sb, ctx.getKind().toString());
      sb.append(":");
      appendJsonString(sb, ctx.getKey());
    }
    sb.append("}");
    return sb.toString();
  }

  private static void appendJsonString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (ch < 0x20) {
            sb.append(String.format("\\u%04x", (int) ch));
          } else {
            sb.append(ch);
          }
        }
      }
    }
    sb.append('"');
  }
}
