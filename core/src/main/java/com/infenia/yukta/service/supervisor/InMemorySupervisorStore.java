// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keeps profiles and bindings in memory; they are gone after a restart. */
@NoArgsConstructor
public class InMemorySupervisorStore implements SupervisorStore {

  /** Profiles by id. */
  private final Map<String, Supervisor> profileById = new ConcurrentHashMap<>();

  /** What each session declares for itself as a whole. */
  private final Map<String, List<SupervisorRef>> refsBySession = new ConcurrentHashMap<>();

  /** Bindings by id. */
  private final Map<String, SupervisorAttachment> bindingById = new ConcurrentHashMap<>();

  @Override
  public Flux<Supervisor> supervisors() {
    return Flux.defer(() -> Flux.fromIterable(profileById.values()))
        .sort(Comparator.comparing(Supervisor::id));
  }

  @Override
  public Mono<Void> saveSupervisor(final Supervisor profile) {
    return Mono.fromRunnable(() -> profileById.put(profile.id(), profile));
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
  public Mono<Void> saveAttachment(final SupervisorAttachment binding) {
    return Mono.fromRunnable(() -> bindingById.put(binding.id(), binding));
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
}
