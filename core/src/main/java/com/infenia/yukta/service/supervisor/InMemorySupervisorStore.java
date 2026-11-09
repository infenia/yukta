// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keeps supervisors and attachments in memory; they are gone after a restart. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods") // the methods of SupervisorStore
public class InMemorySupervisorStore implements SupervisorStore {

  /** Supervisors by id. */
  private final Map<String, Supervisor> profileById = new ConcurrentHashMap<>();

  /** What each session declares for itself as a whole. */
  private final Map<String, List<SupervisorRef>> refsBySession = new ConcurrentHashMap<>();

  /** Attachments by id. */
  private final Map<String, SupervisorAttachment> bindingById = new ConcurrentHashMap<>();

  /** The saved states of each supervisor. */
  private final SupervisorVersions history = new SupervisorVersions();

  /** The hold ledger, in insertion order. */
  private final Set<Hold> ledger = Collections.synchronizedSet(new LinkedHashSet<>());

  @Override
  public Flux<Supervisor> supervisors() {
    return Flux.defer(() -> Flux.fromIterable(profileById.values()))
        .sort(Comparator.comparing(Supervisor::id));
  }

  @Override
  public Mono<Void> saveSupervisor(final Supervisor supervisor) {
    return Mono.fromRunnable(() -> profileById.put(supervisor.id(), supervisor));
  }

  @Override
  public Mono<Boolean> deleteSupervisor(final String id) {
    return Mono.fromSupplier(() -> profileById.remove(id) != null);
  }

  @Override
  public Flux<SupervisorAttachment> attachments() {
    return Flux.defer(() -> Flux.fromIterable(bindingById.values()))
        .sort(Comparator.comparing(SupervisorAttachment::id));
  }

  @Override
  public Mono<Void> saveAttachment(final SupervisorAttachment attachment) {
    return Mono.fromRunnable(() -> bindingById.put(attachment.id(), attachment));
  }

  @Override
  public Mono<Boolean> deleteAttachment(final String id) {
    return Mono.fromSupplier(() -> bindingById.remove(id) != null);
  }

  @Override
  public Mono<List<SupervisorRef>> sessionRefs(final String sessionId) {
    return Mono.fromSupplier(() -> refsBySession.getOrDefault(sessionId, List.of()));
  }

  @Override
  public Mono<Void> saveSessionRefs(final String sessionId, final List<SupervisorRef> refs) {
    return Mono.fromRunnable(
        () -> {
          if (refs.isEmpty()) {
            refsBySession.remove(sessionId);
          } else {
            refsBySession.put(sessionId, List.copyOf(refs));
          }
        });
  }

  @Override
  public Mono<Void> saveVersion(
      final Supervisor supervisor, final Instant savedAt, final int keep) {
    return Mono.fromRunnable(() -> history.record(supervisor, savedAt, keep));
  }

  @Override
  public Flux<SupervisorVersions.Version> versions(final String id) {
    return Flux.defer(() -> Flux.fromIterable(history.list(id)));
  }

  @Override
  public Mono<Void> deleteVersions(final String id) {
    return Mono.fromRunnable(() -> history.forget(id));
  }

  @Override
  public Flux<Hold> holds() {
    return Flux.defer(() -> Flux.fromIterable(List.copyOf(ledger)));
  }

  @Override
  public Mono<Void> saveHold(final Hold hold) {
    return Mono.fromRunnable(() -> ledger.add(hold));
  }

  @Override
  public Mono<Void> deleteHold(final @Nullable String holder, final String executionId) {
    return Mono.fromRunnable(
        () ->
            ledger.removeIf(
                hold ->
                    hold.executionId().equals(executionId)
                        && (holder == null || hold.holder().equals(holder))));
  }
}
