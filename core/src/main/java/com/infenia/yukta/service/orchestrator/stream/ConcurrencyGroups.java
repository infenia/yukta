// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * Server-wide limits on how many nodes of a named group run at once ({@code
 * yukta.concurrency.groups.<name>=<n>}), so heavy nodes such as Gradle builds wait their turn
 * instead of all starting. A node joins a group with {@code engine.concurrencyGroup}; a group
 * without a configured limit is not limited.
 *
 * <p>A node holds a place from its first input until its output ends, however it ends. Waiting
 * nodes are served in arrival order. The limits are read at start-up.
 */
@Component
public final class ConcurrencyGroups {

  private final Map<String, Integer> limits;

  private final Map<String, Group> groups = new ConcurrentHashMap<>();

  /**
   * Reads the limits from the environment.
   *
   * @param environment holds {@code yukta.concurrency.groups.*}
   */
  @Autowired
  public ConcurrencyGroups(final Environment environment) {
    this(
        Binder.get(environment)
            .bind("yukta.concurrency.groups", Bindable.mapOf(String.class, Integer.class))
            .orElse(Map.of()));
  }

  /**
   * Uses the given limits.
   *
   * @param limits the most nodes allowed at once, by group name
   */
  public ConcurrencyGroups(final Map<String, Integer> limits) {
    this.limits = Map.copyOf(limits);
  }

  /**
   * The place of one node run in a group.
   *
   * @param group the group name
   * @return the place, or null when the group has no limit
   */
  @Nullable
  public Place place(@Nullable final String group) {
    final Integer limit = group == null ? null : limits.get(group);
    return limit == null || limit < 1
        ? null
        : new Place(groups.computeIfAbsent(group, _ -> new Group(limit)));
  }

  /** One node run's claim on a group; {@link #release()} is safe to call at any time. */
  @SuppressWarnings("PMD.AvoidUsingVolatile")
  public static final class Place {

    private final Group group;

    // guarded by group
    private boolean held;
    private boolean done;
    private MonoSink<Void> waiter;

    private Place(final Group group) {
      this.group = group;
    }

    /**
     * Completes when the node may start, at once if the group has room.
     *
     * @param onWait runs when the node has to wait
     * @return a Mono that completes with the place granted
     */
    public Mono<Void> acquire(final Runnable onWait) {
      return Mono.<Void>create(
          sink -> {
            if (group.take(this, sink)) {
              sink.success();
            } else {
              onWait.run();
            }
          });
    }

    /** Gives the place back, or leaves the queue; later calls do nothing. */
    public void release() {
      group.release(this);
    }
  }

  private static final class Group {

    private final int limit;
    private final Deque<Place> waiting = new ArrayDeque<>();
    private int running;

    private Group(final int limit) {
      this.limit = limit;
    }

    /** Takes a place if there is room; otherwise queues the sink. */
    private synchronized boolean take(final Place place, final MonoSink<Void> sink) {
      if (place.done) {
        return true;
      }
      if (running < limit) {
        running++;
        place.held = true;
        return true;
      }
      place.waiter = sink;
      waiting.add(place);
      return false;
    }

    private void release(final Place place) {
      MonoSink<Void> next = null;
      synchronized (this) {
        if (place.done) {
          return;
        }
        place.done = true;
        if (place.held) {
          running--;
          final Place first = waiting.poll();
          if (first != null) {
            running++;
            first.held = true;
            next = first.waiter;
          }
        } else {
          waiting.remove(place);
        }
      }
      if (next != null) {
        next.success();
      }
    }
  }
}
