// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.SupervisorBindingInfo;
import com.infenia.yukta.mcp.dto.SupervisorDetached;
import com.infenia.yukta.mcp.dto.SupervisorListing;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Lists, attaches and detaches supervisors for MCP tools. */
@Component
@RequiredArgsConstructor
public class SupervisorProvider {

  /** The running supervisors. */
  private final SupervisorHost host;

  /** Attaches and detaches them. */
  private final SupervisorService service;

  /**
   * The supervisors that run and the bindings attached at run time.
   *
   * @return the listing
   */
  public Mono<SupervisorListing> list() {
    return service
        .bindings()
        .map(SupervisorProvider::info)
        .collectList()
        .map(
            bindings ->
                new SupervisorListing(
                    host.status().stream()
                        .map(
                            s ->
                                new SupervisorListing.RunningSupervisor(
                                    s.id(),
                                    s.kind(),
                                    s.scope(),
                                    s.scopeSessionId(),
                                    s.scopeWorkflowId(),
                                    s.dryRun(),
                                    s.applied(),
                                    s.rejected()))
                        .toList(),
                    bindings,
                    service.types()));
  }

  /**
   * Attaches a profile to a scope.
   *
   * @param profileId an existing profile
   * @param scope {@code global}, {@code session:<id>}, {@code workflow:<id>} or {@code
   *     workflow:<sessionId>/<workflowId>}
   * @param enabled whether it runs; null means yes
   * @param bindingId the binding id; null or blank generates one
   * @return the binding
   */
  public Mono<SupervisorBindingInfo> attach(
      final String profileId, final String scope, final Boolean enabled, final String bindingId) {
    return Mono.fromSupplier(
            () ->
                new SupervisorBinding(
                    bindingId == null || bindingId.isBlank()
                        ? UUID.randomUUID().toString()
                        : bindingId.strip(),
                    profileId,
                    SupervisorScope.parse(scope),
                    enabled == null || enabled))
        .flatMap(service::attach)
        .map(SupervisorProvider::info);
  }

  /**
   * Detaches a binding.
   *
   * @param bindingId the binding id
   * @return the result; an error for an unknown binding
   */
  public Mono<SupervisorDetached> detach(final String bindingId) {
    return service.detach(bindingId).thenReturn(new SupervisorDetached(bindingId, true));
  }

  private static SupervisorBindingInfo info(final SupervisorBinding binding) {
    return new SupervisorBindingInfo(
        binding.id(), binding.profileId(), binding.scope().key(), binding.enabled());
  }
}
