// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.SupervisorTypeDetails;
import com.infenia.yukta.dto.response.SupervisorTypeSummary;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.plugin.control.SupervisorCommonSettings;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.supervisor.SupervisorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** The catalog of supervisor types, like the plugin catalog. Reading needs a viewer key. */
@RestController
@RequestMapping("/api/supervisor-types")
@RequiredArgsConstructor
@Tag(name = "Supervisor types API", description = "The catalog of supervisor types")
public class SupervisorTypesController {

  /** Knows the types and the supervisors made from them. */
  private final SupervisorService service;

  /**
   * List the supervisor types.
   *
   * @return one summary per type, sorted by name
   */
  @GetMapping
  @Operation(summary = "List supervisor types")
  public Mono<ApiResponse<List<SupervisorTypeSummary>>> list() {
    return Mono.fromSupplier(
        () ->
            ApiResponse.success(
                200,
                "Supervisor types retrieved",
                service.types().stream().map(this::summary).toList()));
  }

  /**
   * Describe one type.
   *
   * @param type the type name
   * @return its descriptor, settings schema, common schema, default commands and example; 404 if
   *     unknown
   */
  @GetMapping("/{type}")
  @Operation(summary = "Get a supervisor type")
  public Mono<ApiResponse<SupervisorTypeDetails>> get(@PathVariable final String type) {
    return Mono.fromSupplier(
        () -> {
          final SupervisorDescriptor d = descriptor(type);
          return ApiResponse.success(
              200,
              "Supervisor type retrieved",
              new SupervisorTypeDetails(
                  type,
                  d.displayName(),
                  d.description(),
                  d.usagePattern(),
                  d.category().name(),
                  service.kind(type),
                  d.uiDesign().orElse(null),
                  service.configSchema(type),
                  SupervisorCommonSettings.SCHEMA,
                  service.defaultAllowedCommands(type) == null
                      ? null
                      : service.defaultAllowedCommands(type).stream().sorted().toList(),
                  d.exampleSettings()));
        });
  }

  /**
   * The supervisors of a type.
   *
   * @param type the type name
   * @return the ids of the supervisors of this type; 404 if the type is unknown
   */
  @GetMapping("/{type}/usage")
  @Operation(summary = "List the supervisors of a type")
  public Mono<ApiResponse<List<String>>> usage(@PathVariable final String type) {
    return Mono.fromSupplier(() -> descriptor(type))
        .then(
            service.supervisors().filter(s -> s.type().equals(type)).map(s -> s.id()).collectList())
        .map(ids -> ApiResponse.success(200, "Supervisors retrieved", ids));
  }

  private SupervisorDescriptor descriptor(final String type) {
    return service
        .describe(type)
        .orElseThrow(() -> new NotFoundException("Supervisor type not found: " + type));
  }

  private SupervisorTypeSummary summary(final String type) {
    final SupervisorDescriptor d = descriptor(type);
    return new SupervisorTypeSummary(
        type,
        d.displayName(),
        d.category().name(),
        service.kind(type),
        d.uiDesign().map(UiDesign::icon).orElse(null));
  }
}
