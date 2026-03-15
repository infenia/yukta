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

import com.infenia.yukta.mapper.AppConfigMapper;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.api.ConfigRequest;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.service.SessionService;
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

/**
 * Controller for managing session and application configurations.
 *
 * <p>Provides endpoints to initialize new sessions or update configurations for existing ones at
 * runtime.
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@Tag(
    name = "Config API",
    description = "Endpoints for session initialization and runtime configuration management")
public class ConfigController {

  private final SessionService sessionService;
  private final AppConfigMapper configMapper;

  /**
   * Initialize a new session or update an existing one with the provided configuration.
   *
   * <p>This endpoint allows callers to dynamically configure project paths, workflows, and session
   * metadata. If the session ID already exists, it overrides the current configuration; otherwise,
   * it creates a new session context.
   *
   * @param request the config request containing session identifiers and configuration values
   * @return response entity with success message indicating the configuration has been applied
   */
  @PostMapping
  @Operation(
      summary = "Apply session configuration",
      description =
          "Initializes a new session or updates an existing session's configuration at runtime. "
              + "Configures project paths, workflow definitions, and session metadata.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "Session configuration applied successfully")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "Invalid configuration data provided in the request")
  public Mono<ResponseEntity<ApiResponse<Void>>> applyConfig(
      @Valid @RequestBody final ConfigRequest request) {
    final SessionConfigData configData = configMapper.toData(request);
    return sessionService
        .applyConfig(configData)
        .thenReturn(
            ResponseEntity.ok(
                ApiResponse.success(200, "Configuration applied successfully", null)));
  }
}
