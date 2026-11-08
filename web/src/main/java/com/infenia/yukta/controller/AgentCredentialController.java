// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.supervisor.SupervisorService;
import com.infenia.yukta.service.supervisor.agent.AgentCredentials;
import com.infenia.yukta.service.supervisor.agent.HttpEngineFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Issues, rotates and revokes the credential of a supervisor of type {@code http}. */
@RestController
@RequestMapping("/api/supervisors/{id}/credential")
@RequiredArgsConstructor
@Tag(name = "Agent credentials", description = "The secret an agent uses on /api/agent/v1")
public class AgentCredentialController {

  private final SupervisorService supervisors;
  private final AgentCredentials credentials;

  /**
   * A new credential.
   *
   * @param token the secret; shown once and never readable again
   * @param expiresAt when it stops working, or {@code null}
   */
  public record IssuedView(String token, @Nullable Instant expiresAt) {}

  /**
   * Makes a credential; an earlier one stops working at once (this is also the rotation).
   *
   * @param id the supervisor id
   * @param ttlHours hours it works, or none for no expiry
   * @param allowedAddresses source addresses it may be used from, or none for any
   * @return the token, shown once
   */
  @PostMapping
  @Operation(summary = "Issue or rotate the credential of an agent supervisor")
  public Mono<ApiResponse<IssuedView>> issue(
      @PathVariable final String id,
      @RequestParam(required = false) final Integer ttlHours,
      @RequestParam(required = false) final java.util.List<String> allowedAddresses) {
    if (ttlHours != null && ttlHours < 1) {
      throw new IllegalArgumentException("ttlHours must be at least 1");
    }
    return supervisors
        .supervisors()
        .filter(found -> found.id().equals(id))
        .next()
        .switchIfEmpty(Mono.error(new NotFoundException("Supervisor not found: " + id)))
        .map(
            found -> {
              if (!HttpEngineFactory.HTTP_TYPE.equals(found.type())) {
                throw new ConflictException("Only a supervisor of type http has a credential");
              }
              final AgentCredentials.Issued issued =
                  credentials.issue(
                      id,
                      ttlHours == null ? null : Duration.ofHours(ttlHours),
                      allowedAddresses == null
                          ? java.util.Set.of()
                          : java.util.Set.copyOf(allowedAddresses));
              return ApiResponse.success(
                  201, "Credential issued", new IssuedView(issued.token(), issued.expiresAt()));
            });
  }

  /**
   * Cuts the credential at once.
   *
   * @param id the supervisor id
   * @return nothing; 404 if it had none
   */
  @DeleteMapping
  @Operation(summary = "Revoke the credential of an agent supervisor")
  public Mono<ApiResponse<Void>> revoke(@PathVariable final String id) {
    if (!credentials.revoke(id)) {
      throw new NotFoundException("Supervisor " + id + " has no credential");
    }
    return Mono.just(ApiResponse.success(200, "Credential revoked", null));
  }
}
