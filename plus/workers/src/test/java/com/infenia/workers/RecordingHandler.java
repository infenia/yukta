// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.SenderContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the observations started, stopped and failed, and writes a {@code traceparent} into a
 * sender's carrier as a tracer's propagating handler would.
 */
final class RecordingHandler implements ObservationHandler<Observation.Context> {

  /** The header a sender's carrier gets. */
  /* default */ static final String TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  /** Observations started, in order. */
  /* default */ final List<Observation.Context> started = new CopyOnWriteArrayList<>();

  /** Observations stopped, in order. */
  /* default */ final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

  /** Observations that recorded an error. */
  /* default */ final List<Observation.Context> failed = new CopyOnWriteArrayList<>();

  /** A registry that reports to this handler. */
  /* default */ ObservationRegistry registry() {
    final ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(this);
    return registry;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onStart(final Observation.Context context) {
    started.add(context);
    if (context instanceof SenderContext<?> sender) {
      final SenderContext<Object> typed = (SenderContext<Object>) sender;
      typed.getSetter().set(typed.getCarrier(), "traceparent", TRACEPARENT);
    }
  }

  @Override
  public void onError(final Observation.Context context) {
    failed.add(context);
  }

  @Override
  public void onStop(final Observation.Context context) {
    stopped.add(context);
  }

  @Override
  public boolean supportsContext(final Observation.Context context) {
    return true;
  }
}
