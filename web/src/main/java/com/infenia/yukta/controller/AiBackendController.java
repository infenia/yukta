// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.plugin.ai.AiBackend;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Lists the AI backends a plugin can be pointed at. */
@RestController
@RequestMapping("/api/ai/backends")
@RequiredArgsConstructor
@Tag(name = "AI Backend API", description = "Endpoints for discovering model and agent backends")
public class AiBackendController {

  /** The backends in this build; none when no plugin offers any. */
  private final ObjectProvider<AiBackend> backends;

  /**
   * List the backends with the options each one reads.
   *
   * @return the backends
   */
  @GetMapping
  @Operation(
      summary = "List AI backends",
      description =
          "Lists the model and agent backends a plugin's backend option can select, each with the"
              + " options it reads (name, type, default, description).")
  public Mono<ResponseEntity<ApiResponse<List<AiBackend.Info>>>> list() {
    return Mono.fromCallable(() -> backends.orderedStream().map(AiBackend::info).toList())
        .map(
            list ->
                ResponseEntity.ok(
                    ApiResponse.success(
                        HttpStatus.OK.value(), "AI backends retrieved successfully", list)));
  }
}
