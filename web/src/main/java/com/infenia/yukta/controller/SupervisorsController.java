// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** The supervisors and control-plane plugins running on the control bus. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Supervisors API", description = "Supervisors and control planes on the control bus")
public class SupervisorsController {

  /** Runs the supervisors. */
  private final SupervisorHost supervisors;

  /**
   * List the running supervisors and control-plane plugins.
   *
   * @return one entry per supervisor with its kind, review order and command counts since startup
   */
  @GetMapping("/supervisors")
  @Operation(
      summary = "List supervisors",
      description =
          "Lists the supervisors and control-plane plugins running on the control bus, in review"
              + " order, with how many commands each applied, had refused, only recorded (dry-run)"
              + " or dropped (rate limit) since startup")
  public Mono<ApiResponse<List<SupervisorInfo>>> list() {
    return Mono.fromCallable(supervisors::status)
        .map(status -> ApiResponse.success(200, "Supervisors retrieved", status));
  }
}
