// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.supervisor.ControlReviewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Tells a client who it is and what it may do. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "Session API", description = "Who is calling and what the key may do")
public class MeController {

  private final ControlReviewer reviewer;

  /**
   * The caller and what supervision means for it.
   *
   * @param authenticated whether a key was presented
   * @param keyName the key's name, or {@code null}
   * @param role the key's role
   * @param sessions the sessions the key is limited to, or {@code null} for all
   * @param authEnabled whether keys are configured
   * @param permissions what the role allows
   * @param supervision whether the default supervisor carries what nobody owns, and how many
   *     supervisors own something the caller can reach
   */
  public record Me(
      boolean authenticated,
      @Nullable String keyName,
      ApiRole role,
      @Nullable List<String> sessions,
      boolean authEnabled,
      CallerIdentity.Permissions permissions,
      ControlReviewer.Summary supervision) {

    /** Copies the sessions. */
    public Me {
      sessions = sessions == null ? null : List.copyOf(sessions);
    }
  }

  /**
   * Describes the caller.
   *
   * @return the key's name, role, sessions and permissions
   */
  @GetMapping("/me")
  @Operation(
      summary = "Who am I",
      description =
          "The name, role and sessions of the API key of this request, and what it may do"
              + " (start and control runs, edit and delete sessions, change limits, send"
              + " commands). With no key configured the role is ADMIN and authEnabled is false."
              + " Never returns the key or its hash. `supervision` says whether the default"
              + " supervisor carries what nobody owns and how many supervisors own something the"
              + " key can reach.")
  @SuppressWarnings("PMD.ShortMethodName")
  public Mono<ApiResponse<Me>> me() {
    return Mono.deferContextual(
        ctx -> {
          final CallerIdentity.Whoami who = CallerIdentity.whoami(ctx);
          return Mono.just(
              ApiResponse.success(
                  200,
                  "Caller described",
                  new Me(
                      who.authenticated(),
                      who.keyName(),
                      who.role(),
                      who.sessions(),
                      who.authEnabled(),
                      who.permissions(),
                      reviewer.summary(who.sessions()))));
        });
  }
}
