// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keeps profiles and bindings in memory; they are gone after a restart. */
@NoArgsConstructor
public class InMemorySupervisorStore implements SupervisorStore {

  /** Profiles by id. */
  private final Map<String, SupervisorProfile> profileById = new ConcurrentHashMap<>();

  /** Bindings by id. */
  private final Map<String, SupervisorBinding> bindingById = new ConcurrentHashMap<>();

  @Override
  public Flux<SupervisorProfile> profiles() {
    return Flux.defer(() -> Flux.fromIterable(profileById.values()))
        .sort(Comparator.comparing(SupervisorProfile::id));
  }

  @Override
  public Mono<Void> saveProfile(final SupervisorProfile profile) {
    return Mono.fromRunnable(() -> profileById.put(profile.id(), profile));
  }

  @Override
  public Mono<Boolean> deleteProfile(final String id) {
    return Mono.fromSupplier(() -> profileById.remove(id) != null);
  }

  @Override
  public Flux<SupervisorBinding> bindings() {
    return Flux.defer(() -> Flux.fromIterable(bindingById.values()))
        .sort(Comparator.comparing(SupervisorBinding::id));
  }

  @Override
  public Mono<Void> saveBinding(final SupervisorBinding binding) {
    return Mono.fromRunnable(() -> bindingById.put(binding.id(), binding));
  }

  @Override
  public Mono<Boolean> deleteBinding(final String id) {
    return Mono.fromSupplier(() -> bindingById.remove(id) != null);
  }
}
