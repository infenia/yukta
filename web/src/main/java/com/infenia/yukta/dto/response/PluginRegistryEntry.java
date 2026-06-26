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
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Record representing an entry in the plugin registry.
 *
 * @param type the plugin type
 * @param category the plugin category
 * @param status the plugin status
 */
@Schema(description = "Record representing an entry in the plugin registry")
public record PluginRegistryEntry(
    @Schema(description = "The plugin type", example = "gradle") String type,
    @Schema(
            description = "The plugin category",
            example = "PROCESSOR",
            allowableValues = {"TRIGGER", "PROCESSOR", "TERMINAL"})
        String category,
    @Schema(
            description = "The plugin status",
            example = "ACTIVE",
            allowableValues = {"ACTIVE", "INACTIVE", "ERROR"})
        String status) {}
