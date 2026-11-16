// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static com.infenia.yukta.service.agent.AgentTestSupport.settings;
import static com.infenia.yukta.service.agent.AgentTestSupport.spec;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * The load the plan names: 10 conversations, 50 events a second in all, 20 stream clients. It
 * prints how long an event takes from the agent to the slowest client and what the heap grew by,
 * and asserts only generous bounds. Run longer with {@code -Dyukta.agents.load.seconds=60}.
 */
@NoArgsConstructor
@SuppressWarnings({"PMD.SystemPrintln", "PMD.DoNotCallGarbageCollectionExplicitly"})
class AgentConversationLoadTest {

  private static final int CONVERSATIONS = 10;
  private static final int CLIENTS = 20;
  private static final int EVENTS_PER_SECOND = 50;

  @Test
  void tenConversationsFiftyEventsPerSecondTwentyClients() throws Exception {
    final int seconds = Integer.getInteger("yukta.agents.load.seconds", 3);
    final InMemoryAgentConversationStore store =
        new InMemoryAgentConversationStore(500, 5000, 262_144);
    final AgentConversationService service =
        new AgentConversationService(
            store,
            new InputRedactor(List.of()),
            settings(64, 32, 5000),
            JsonMapper.builder().build(),
            Clock.systemUTC());
    final long[] worst = new long[CLIENTS];
    final AtomicLong delivered = new AtomicLong();
    final AtomicLong lagged = new AtomicLong();
    final List<Disposable> clients = new ArrayList<>();
    for (int c = 0; c < CLIENTS; c++) {
      final int client = c;
      clients.add(
          service
              .changes()
              .publishOn(Schedulers.boundedElastic())
              .subscribe(
                  change -> {
                    if (change instanceof AgentChange.EventAppended appended) {
                      final long lag =
                          System.currentTimeMillis() - appended.event().timestamp().toEpochMilli();
                      worst[client] = Math.max(worst[client], lag);
                      delivered.incrementAndGet();
                    }
                  },
                  error -> lagged.incrementAndGet()));
    }
    final List<AgentConversationHandle> handles = new ArrayList<>();
    for (int i = 0; i < CONVERSATIONS; i++) {
      final AgentConversationHandle handle =
          java.util.Objects.requireNonNull(service.open(spec("message"), "root").block());
      handle.state(AgentState.RUNNING, null);
      handles.add(handle);
    }
    service.flush().block();
    System.gc();
    final long before = used();

    final long ticks = (long) seconds * EVENTS_PER_SECOND;
    final long started = System.nanoTime();
    final Disposable feed =
        reactor.core.publisher.Flux.interval(
                Duration.ofNanos(TimeUnit.SECONDS.toNanos(1) / EVENTS_PER_SECOND))
            .take(ticks)
            .subscribe(tick ->
                handles
                    .get((int) (tick % CONVERSATIONS))
                    .emit(
                        AgentEventKind.MESSAGE,
                        "m" + tick,
                        Map.of("role", "agent", "text", "line " + tick, "more", false)));
    reactor.core.publisher.Flux.interval(Duration.ofMillis(50))
        .takeUntil(i -> feed.isDisposed())
        .blockLast(Duration.ofSeconds(seconds + 30L));
    service.flush().block();
    TimeUnit.MILLISECONDS.sleep(500);
    final double took = (System.nanoTime() - started) / 1e9;
    System.gc();
    final long grew = used() - before;
    final long slowest = java.util.Arrays.stream(worst).max().orElse(0);

    System.out.printf(
        "LOAD conversations=%d clients=%d seconds=%.1f events=%d delivered=%d slowestClientLagMs=%d"
            + " heapGrowthKb=%d laggedClients=%d%n",
        CONVERSATIONS, CLIENTS, took, ticks, delivered.get(), slowest, grew / 1024, lagged.get());

    assertThat(lagged.get()).as("no client may fall behind").isZero();
    // every client gets every event, and the state events of opening the conversations
    assertThat(delivered.get()).isGreaterThanOrEqualTo(ticks * CLIENTS);
    assertThat(slowest).as("the slowest client's lag in ms").isLessThan(2_000);
    clients.forEach(Disposable::dispose);
    service.destroy();
  }

  private static long used() {
    final Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
