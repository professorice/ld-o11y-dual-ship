package com.example.demo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Executors;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.LDClient;
import com.launchdarkly.sdk.server.LDConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

public final class DemoApplication {

  static final String FLAG_KEY = "demo-flag";
  private static final int PORT = 8080;
  private static final Tracer TRACER =
      GlobalOpenTelemetry.getTracer("ld-o11y-demo-java");

  public static void main(String[] args) throws Exception {
    String sdkKey = System.getenv("LAUNCHDARKLY_SDK_KEY");
    if (sdkKey == null || sdkKey.isBlank()) {
      System.err.println("set LAUNCHDARKLY_SDK_KEY in .env (see ./scripts/run.sh)");
      System.exit(1);
    }

    LDConfig config = new LDConfig.Builder()
        .hooks(Components.hooks().setHooks(Collections.singletonList(new MonitoringHook())))
        .build();

    try (LDClient ldClient = new LDClient(sdkKey, config)) {
      HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
      server.setExecutor(Executors.newCachedThreadPool());
      server.createContext("/hello", exchange -> handleHello(ldClient, exchange));
      server.start();
      System.out.printf("listening on :%d (GET /hello?user=demo-user)%n", PORT);
      Thread.currentThread().join();
    }
  }

  private static void handleHello(LDClient ldClient, HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }

    String user = queryParam(exchange.getRequestURI(), "user", "demo-user");
    LDContext context = LDContext.builder(user).kind("user").build();

    // Explicit OTel SERVER span so MonitoringHook can parent its child span on Span.current().
    String spanName = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
    Span requestSpan = TRACER.spanBuilder(spanName).setSpanKind(SpanKind.SERVER).startSpan();
    try (Scope scope = requestSpan.makeCurrent()) {
      EvaluationDetail<Boolean> detail =
          ldClient.boolVariationDetail(FLAG_KEY, context, false);

      String body = "hello " + user + " flag=" + FLAG_KEY + " value=" + detail.getValue() + "\n";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    } finally {
      requestSpan.end();
    }
  }

  private static String queryParam(URI uri, String name, String defaultValue) {
    String query = uri.getRawQuery();
    if (query == null || query.isEmpty()) {
      return defaultValue;
    }
    for (String part : query.split("&")) {
      int eq = part.indexOf('=');
      String key = eq >= 0 ? part.substring(0, eq) : part;
      if (name.equals(key)) {
        return eq >= 0 ? part.substring(eq + 1) : "";
      }
    }
    return defaultValue;
  }
}
