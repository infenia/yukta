// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An answer sent with the one-time token of a delivery, without an API key.
 *
 * @param requestId the input request
 * @param tier the tier the delivery was for
 * @param expires when the token expires, in epoch seconds, as in the delivery
 * @param token the token of the delivery
 * @param decision an option id, or a short decision such as {@code approve} or {@code reject}
 * @param text free text that goes with the decision, at most 8 KB
 * @param data structured data that goes with the decision, at most 16 KB as JSON
 * @param rationale why, at most 8 KB
 */
@Schema(description = "An answer sent with the one-time token of a delivery")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores an unmodifiable copy")
public record TokenAnswerRequest(
    @NotBlank @Size(max = 128) String requestId,
    @NotBlank @Size(max = 128) String tier,
    @Positive long expires,
    @NotBlank @Size(max = 256) String token,
    @NotBlank @Size(max = 128) String decision,
    @Size(max = 8192) @Nullable String text,
    @Nullable Map<String, Object> data,
    @Size(max = 8192) @Nullable String rationale) {

  /** Copies the data. */
  public TokenAnswerRequest {
    data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }
}
