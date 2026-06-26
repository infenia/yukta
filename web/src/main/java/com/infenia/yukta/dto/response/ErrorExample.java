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
 * Represents a common error example in session creation.
 *
 * @param error the error type or name
 * @param cause the cause of the error
 * @param resolution the resolution or fix for the error
 */
@Schema(description = "A common error example with cause and resolution")
public record ErrorExample(
    @Schema(description = "The error type or name") String error,
    @Schema(description = "The cause of the error") String cause,
    @Schema(description = "The resolution or fix for the error") String resolution) {}
