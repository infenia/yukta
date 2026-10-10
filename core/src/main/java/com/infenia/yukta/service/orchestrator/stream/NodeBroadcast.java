// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;

/**
 * A node's output shared by all of its downstream edges, with real backpressure.
 *
 * <p>Wraps {@code publish(prefetch)}, which only requests what its slowest subscriber can take. A
 * published flux must be connected <em>after</em> its subscribers have attached, otherwise items
 * emitted before the first subscriber are lost. The number of subscribers is known when the
 * workflow is assembled (one per outgoing edge, registered through {@link #expectSubscriber()}), so
 * the source is connected as soon as the last expected subscriber has subscribed. A node without
 * outgoing edges is drained by a private subscriber when the connectors run.
 *
 * <p>A downstream edge that never subscribes keeps the source from connecting, so siblings of that
 * edge receive nothing.
 */
final class NodeBroadcast extends Flux<Message<?>> {

  /** The shared, not yet connected, source. */
  private final ConnectableFlux<Message<?>> hot;

  /** Receives the connection so it is disposed with the execution. */
  private final List<Disposable> disposables;

  /** Number of outgoing edges that will subscribe. */
  private final AtomicInteger expected = new AtomicInteger();

  /** Number of subscriptions received so far. */
  private final AtomicInteger subscribed = new AtomicInteger();

  /** Guards connecting exactly once. */
  private final AtomicBoolean connected = new AtomicBoolean();

  /* default */ NodeBroadcast(
      final ConnectableFlux<Message<?>> hot, final List<Disposable> disposables) {
    super();
    this.hot = hot;
    this.disposables = disposables;
  }

  /** Registers one more outgoing edge that will subscribe before the source is connected. */
  /* default */ void expectSubscriber() {
    expected.incrementAndGet();
  }

  /** Connector entry point: drains and connects a node that nobody is going to subscribe to. */
  /* default */ void connectIfUnobserved() {
    if (expected.get() == 0 && connected.compareAndSet(false, true)) {
      hot.subscribe(_ -> {}, _ -> {});
      disposables.add(hot.connect());
    }
  }

  @Override
  public void subscribe(final CoreSubscriber<? super Message<?>> actual) {
    hot.subscribe(actual);
    if (subscribed.incrementAndGet() >= expected.get() && connected.compareAndSet(false, true)) {
      disposables.add(hot.connect());
    }
  }
}
