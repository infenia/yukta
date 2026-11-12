// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@NoArgsConstructor
class RunTelemetryTraceTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final RunTelemetry telemetry = new RunTelemetry(meters, ObservationRegistry.create());

  /** Puts a span in the context of every observation, as the tracing handler of boot does. */
  private static final class SpanHandler implements ObservationHandler<Observation.Context> {
    private final Span span;

    private SpanHandler(final Span span) {
      this.span = span;
    }

    @Override
    public boolean supportsContext(final Observation.Context context) {
      return true;
    }

    @Override
    public void onStart(final Observation.Context context) {
      context.put(TracingContext.class, tracingOf());
    }

    private TracingContext tracingOf() {
      final TracingContext tracing = new TracingContext();
      tracing.setSpan(span);
      return tracing;
    }
  }

  /** A registry whose observations carry a span with the given trace id. */
  private static ObservationRegistry tracedBy(final String traceId) {
    final TraceContext trace = Mockito.mock(TraceContext.class);
    Mockito.when(trace.traceId()).thenReturn(traceId);
    final Span span = Mockito.mock(Span.class);
    Mockito.when(span.context()).thenReturn(trace);
    final ObservationRegistry traced = ObservationRegistry.create();
    traced.observationConfig().observationHandler(new SpanHandler(span));
    return traced;
  }

  @Test
  void runStartedReturnsTheTraceIdWhenTracedAndNullOtherwise() {
    assertThat(telemetry.runStarted("e0", "s", "w")).isNull();

    assertThat(
            new RunTelemetry(meters, tracedBy("4bf92f3577b34da6a3ce929d0e0e4736"))
                .runStarted("e1", "s", "w"))
        .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
  }

  @Test
  void runStartedReturnsNullWhenTheTraceIdIsEmpty() {
    assertThat(new RunTelemetry(meters, tracedBy("")).runStarted("e1", "s", "w")).isNull();
  }
}
