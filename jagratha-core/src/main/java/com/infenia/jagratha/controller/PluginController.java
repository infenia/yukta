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
package com.infenia.jagratha.controller;

import com.infenia.jagratha.model.ApiResponse;
import com.infenia.jagratha.model.PluginDetails;
import com.infenia.jagratha.model.PluginSummary;
import com.infenia.jagratha.service.WorkflowRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Controller for plugin information. */
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
@Tag(name = "Plugin API", description = "Endpoints for discovering workflow plugins")
public class PluginController {

  private final WorkflowRegistry registry;

  /**
   * List all available plugins.
   *
   * @return list of plugin summaries
   */
  @GetMapping
  @Operation(summary = "List plugins", description = "Lists all registered workflow plugins")
  public Mono<ApiResponse<List<PluginSummary>>> listPlugins() {
    return Mono.just(registry.listPlugins())
        .map(
            plugins ->
                plugins.stream().map(p -> new PluginSummary(p.getType(), p.getCategory())).toList())
        .map(summaries -> ApiResponse.success(200, "Plugins retrieved successfully", summaries));
  }

  /**
   * Get details of a specific plugin.
   *
   * @param type the plugin type
   * @return plugin details
   */
  @GetMapping("/{type}")
  @Operation(summary = "Get plugin details", description = "Retrieves details of a specific plugin")
  public Mono<ResponseEntity<ApiResponse<PluginDetails>>> getPluginDetails(
      @PathVariable final String type) {
    return Mono.fromCallable(() -> registry.get(type))
        .map(
            p -> {
              if (p != null) {
                return ResponseEntity.ok(
                    ApiResponse.success(
                        200,
                        "Plugin details retrieved",
                        new PluginDetails(
                            p.getType(),
                            p.getCategory(),
                            p.getDescription(),
                            p.getUsagePattern())));
              }
              return ResponseEntity.notFound().build();
            });
  }
}
