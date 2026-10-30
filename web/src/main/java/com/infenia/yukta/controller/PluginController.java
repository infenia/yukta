// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.PluginDetails;
import com.infenia.yukta.dto.response.PluginSummary;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Controller for plugin information. */
@Validated
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
@Tag(name = "Plugin API", description = "Endpoints for discovering workflow plugins")
public class PluginController {
  /** Content type constant for JSON responses. */
  private static final String APPLICATION_JSON = "application/json";

  /** The plugin registry for accessing plugin information. */
  private final PluginRegistry registry;

  /** The sessions, whose workflows use the plugins. */
  private final SessionService sessionService;

  /**
   * One workflow that uses a plugin.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param nodes how many of its nodes are of the plugin's type
   */
  public record Usage(String sessionId, String workflowId, int nodes) {}

  /**
   * List all available plugins.
   *
   * @return list of plugin summaries
   */
  @GetMapping
  @Operation(
      summary = "List plugins",
      description =
          "Lists all registered workflow plugins. Response is non-blocking and returned"
              + " asynchronously via Mono.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "Plugins retrieved successfully",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "500",
      description = "Internal server error",
      content = @Content(mediaType = APPLICATION_JSON))
  public Mono<ResponseEntity<ApiResponse<List<PluginSummary>>>> listPlugins() {
    return Mono.fromCallable(registry::listPlugins)
        .map(
            plugins ->
                plugins.stream().map(p -> new PluginSummary(p.getType(), p.getCategory())).toList())
        .map(
            summaries ->
                ResponseEntity.ok(
                    ApiResponse.success(
                        HttpStatus.OK.value(), "Plugins retrieved successfully", summaries)));
  }

  /**
   * Get details of a specific plugin.
   *
   * @param type the plugin type
   * @param exchange implicit Spring parameter used to extract request path for error responses
   * @return plugin details
   */
  @GetMapping("/{type}")
  @Operation(
      summary = "Get plugin details",
      description =
          "Retrieves details of a specific plugin. Response is non-blocking and returned"
              + " asynchronously via Mono.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "Plugin details retrieved successfully",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Plugin not found",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "500",
      description = "Internal server error",
      content = @Content(mediaType = APPLICATION_JSON))
  public Mono<ResponseEntity<ApiResponse<PluginDetails>>> getPluginDetails(
      @Parameter(description = "The unique identifier of the plugin type") @PathVariable
          final String type,
      final ServerWebExchange exchange) {
    return Mono.fromCallable(() -> registry.get(type))
        .flatMap(Mono::justOrEmpty)
        .map(
            p ->
                ResponseEntity.ok(
                    ApiResponse.success(
                        HttpStatus.OK.value(),
                        "Plugin details retrieved",
                        new PluginDetails(
                            p.getType(),
                            p.getCategory(),
                            p.describe().description(),
                            p.describe().usagePattern(),
                            p.describe().uiDesign().orElse(null),
                            p.getOutputPorts(),
                            p.getConfigSchema()))))
        .switchIfEmpty(
            Mono.fromSupplier(
                () -> {
                  final List<ApiResponse.FieldError> errors =
                      List.of(
                          new ApiResponse.FieldError("type", "Plugin not found: '" + type + "'"));
                  final var request = exchange.getRequest();
                  final String path = request.getPath().value();
                  return ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(
                          ApiResponse.error(
                              HttpStatus.NOT_FOUND.value(),
                              "Not Found",
                              "Plugin not found",
                              path,
                              errors));
                }));
  }

  /**
   * List the workflows that use a plugin.
   *
   * @param type the plugin type
   * @return each workflow with nodes of that type, by session and workflow ID
   */
  @GetMapping("/{type}/usage")
  @Operation(
      summary = "List the workflows that use a plugin",
      description =
          "Every workflow of every session with at least one node of the type, and how many,"
              + " sorted by session and workflow ID. An unknown type is used nowhere.")
  public Mono<ApiResponse<List<Usage>>> getPluginUsage(
      @Parameter(description = "The unique identifier of the plugin type") @PathVariable
          final String type) {
    return sessionService
        .getAllSessionConfigs()
        .flatMapIterable(
            session ->
                session.workflows().values().stream()
                    .map(
                        workflow ->
                            new Usage(
                                session.sessionId(),
                                workflow.workflowId(),
                                (int)
                                    workflow.nodes().stream()
                                        .filter(node -> type.equals(node.type()))
                                        .count()))
                    .filter(usage -> usage.nodes() > 0)
                    .toList())
        .sort(Comparator.comparing(Usage::sessionId).thenComparing(Usage::workflowId))
        .collectList()
        .map(usages -> ApiResponse.success(HttpStatus.OK.value(), "Plugin usage", usages));
  }
}
