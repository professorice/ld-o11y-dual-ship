package com.example.demo;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.server.integrations.Hook;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;

/**
 * Puts a feature_flag span EVENT on an OTel child span parented to the active HTTP span
 * (see DemoApplication). Events on Span.current() alone are dropped by the Datadog tracer.
 */
public final class MonitoringHook extends Hook {

  private static final String HOOK_NAME = "LaunchDarkly Monitoring Hook";
  private static final String SPAN_KEY = "monitoringSpan";
  private static final String EVENT_NAME = "feature_flag";
  private static final String TRACER_NAME = "launchdarkly-client";

  public MonitoringHook() {
    super(HOOK_NAME);
  }

  @Override
  public Map<String, Object> beforeEvaluation(
      EvaluationSeriesContext seriesContext,
      Map<String, Object> seriesData) {

    Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);
    Span span = tracer.spanBuilder(seriesContext.method)
        .setParent(Context.current().with(Span.current()))
        .startSpan();

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

    span.addEvent(
        EVENT_NAME,
        Attributes.builder()
            .put("feature_flag.key", seriesContext.flagKey)
            .put("feature_flag.provider.name", "LaunchDarkly")
            .put("feature_flag.context.id", seriesContext.context.getFullyQualifiedKey())
            .put("feature_flag.result.value", detail.getValue().toJsonString())
            .build());

    span.end();
    return seriesData;
  }
}
