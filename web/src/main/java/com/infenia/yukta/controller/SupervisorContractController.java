// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.plugin.control.SupervisorContract;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Tells a client which version of the supervisor contract this server speaks. */
@RestController
@RequestMapping("/api/supervisor-contract")
@Tag(name = "Supervisor contract API", description = "The version and operations of the contract")
public class SupervisorContractController {

  /**
   * The contract this server implements.
   *
   * @param version the major version
   * @param operations the names by group: read, act, signals, directives, causes, outcomes
   */
  public record ContractView(int version, Map<String, List<String>> operations) {

    /** Copies the operations so the view keeps its own unmodifiable, ordered map. */
    public ContractView {
      operations = Collections.unmodifiableMap(new LinkedHashMap<>(operations));
    }
  }

  /**
   * Describe the contract.
   *
   * @return the version and the operation names
   */
  @GetMapping
  @Operation(summary = "Get the supervisor contract version and operations")
  public Mono<ApiResponse<ContractView>> get() {
    return Mono.fromSupplier(
        () ->
            ApiResponse.success(
                200,
                "Supervisor contract retrieved",
                new ContractView(SupervisorContract.VERSION, SupervisorContract.operations())));
  }
}
