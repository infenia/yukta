// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.edition.EditionInfo;
import com.infenia.yukta.model.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Which edition of Yukta this server is. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Edition API", description = "Edition, licence and features of this build")
public class EditionController {

  /** What this build is. */
  private final EditionInfo edition;

  /**
   * Show the edition.
   *
   * @return the edition, its licence, its features and where its source is
   */
  @GetMapping("/edition")
  @Operation(
      summary = "Show the edition",
      description =
          "Returns the edition of this build (COMMUNITY, PLUS or ENTERPRISE), its licence, the"
              + " optional features it has and, where the licence asks for it, where the source is")
  public Mono<ApiResponse<EditionInfo>> show() {
    return Mono.just(ApiResponse.success(200, "Edition retrieved", edition));
  }
}
