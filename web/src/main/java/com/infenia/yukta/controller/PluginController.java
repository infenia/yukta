/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.PluginDetails;
import com.infenia.yukta.dto.response.PluginSummary;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.plugin.PluginRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Tag(name = "Plugin API", description = "Endpoints for discovering workflow plugins")
public class PluginController {
  /** Content type constant for JSON responses. */
  private static final String APPLICATION_JSON = "application/json";

  /** The plugin registry for accessing plugin information. */
  private final PluginRegistry registry;

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
    log.atInfo().log("listPlugins reached");
    return Mono.fromCallable(registry::listPlugins)
        .map(
            plugins ->
                plugins.stream().map(p -> new PluginSummary(p.getType(), p.getCategory())).toList())
        .map(
            summaries ->
                ResponseEntity.ok(
                    ApiResponse.success(
                        HttpStatus.OK.value(), "Plugins retrieved successfully", summaries)))
        .onErrorResume(
            e ->
                Mono.just(
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(
                            ApiResponse.error(
                                500,
                                "Internal Server Error",
                                "Failed to retrieve plugins: " + e.getMessage(),
                                "/api/plugins",
                                List.of()))));
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
    log.atInfo().log("getPluginDetails reached: type={}", type);
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
                            p.getDescription(),
                            p.getUsagePattern(),
                            p.getUiDesign().orElse(null),
                            p.getOutputPorts()))))
        .switchIfEmpty(
            Mono.fromSupplier(
                () -> {
                  final List<ApiResponse.FieldError> errors =
                      List.of(
                          new ApiResponse.FieldError("type", "Plugin not found: '" + type + "'"));
                  @SuppressWarnings("PMD.LawOfDemeter")
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
}
