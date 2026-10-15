// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.worker.WorkerInfo;
import com.infenia.yukta.service.worker.WorkerRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** The workers connected to the control plane. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Workers API", description = "Workers connected to the control plane")
public class WorkersController {

  /** The connected workers. */
  private final WorkerRegistry workers;

  /**
   * List the connected workers.
   *
   * @return one entry per live worker with its pools, plugin types, capacity and free slots
   */
  @GetMapping("/workers")
  @Operation(
      summary = "List workers",
      description =
          "Lists the workers that are connected and keep their lease, ordered by id, with the"
              + " pools they serve, the plugin types they may run, their capacity and the slots in"
              + " use at their last heartbeat")
  public Mono<ApiResponse<List<WorkerInfo>>> list() {
    return Mono.fromCallable(workers::list)
        .map(list -> ApiResponse.success(200, "Workers retrieved", list));
  }
}
