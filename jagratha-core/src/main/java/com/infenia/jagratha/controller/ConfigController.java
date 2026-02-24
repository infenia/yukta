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

import com.infenia.jagratha.mapper.AppConfigMapper;
import com.infenia.jagratha.model.ApiResponse;
import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Controller for application configuration. */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@Tag(name = "Config API", description = "Endpoints for runtime configuration")
public class ConfigController {

  private final SessionService sessionService;
  private final AppConfigMapper configMapper;

  /**
   * Update configuration at runtime.
   *
   * @param request the config request containing new configuration values
   * @return response entity with success message
   */
  @PostMapping
  @Operation(
      summary = "Update configuration",
      description = "Updates the application configuration at runtime for a session")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "Configuration updated successfully")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "Invalid configuration data")
  public Mono<ResponseEntity<ApiResponse<Void>>> updateConfig(
      @Valid @RequestBody final ConfigRequest request) {
    final AppConfigData configData = configMapper.toData(request);
    return sessionService
        .applyConfigOverrides(configData)
        .thenReturn(
            ResponseEntity.ok(
                ApiResponse.success(200, "Configuration updated successfully", null)));
  }
}
