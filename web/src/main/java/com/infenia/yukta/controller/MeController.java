// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Tells a client who it is and what it may do. */
@RestController
@NoArgsConstructor
@RequestMapping("/api")
@Tag(name = "Session API", description = "Who is calling and what the key may do")
public class MeController {

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
              + " Never returns the key or its hash.")
  @SuppressWarnings("PMD.ShortMethodName")
  public Mono<ApiResponse<CallerIdentity.Whoami>> me() {
    return Mono.deferContextual(
        ctx -> Mono.just(ApiResponse.success(200, "Caller described", CallerIdentity.whoami(ctx))));
  }
}
