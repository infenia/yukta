// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The answer to an input request. Which fields it needs depends on the request: an option id as the
 * decision for CHOICE (and APPROVAL with options), text for TEXT, data that fits the schema for
 * STRUCTURED. An answer of the wrong shape is refused with 422 and leaves the request open.
 *
 * @param decision an option id, or a short decision such as {@code approve} or {@code reject}
 * @param text free text that goes with the decision, at most 8 KB
 * @param data structured data that goes with the decision, at most 16 KB as JSON
 * @param rationale why, at most 8 KB; recorded in the execution log (cut to 500 characters)
 * @param expectedVersion the version of the request the caller saw: the answer is refused with 409
 *     when the request changed since
 */
@Schema(description = "The answer to an input request")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores an unmodifiable copy")
public record InputAnswerRequest(
    @Schema(description = "An option id, or a decision such as approve or reject", example = "ok")
        @NotBlank(message = "decision is required")
        @Size(max = 128, message = "decision is at most 128 characters")
        String decision,
    @Schema(description = "Free text that goes with the decision")
        @Size(max = 8192, message = "text is at most 8192 characters")
        @Nullable String text,
    @Schema(description = "Structured data that goes with the decision")
        @Nullable Map<String, Object> data,
    @Schema(description = "Why; recorded in the execution log")
        @Size(max = 8192, message = "rationale is at most 8192 characters")
        @Nullable String rationale,
    @Schema(description = "The version of the request the caller saw")
        @PositiveOrZero(message = "expectedVersion must not be negative")
        @Nullable Long expectedVersion) {

  /** Copies the data. */
  public InputAnswerRequest {
    // Map.copyOf rejects null values, and answer data may hold them
    data = data == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }
}
