// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The answer to a request.
 *
 * <p>It comes from a person, an agent or a model, so a plugin that hands it to a model must treat
 * it as untrusted text.
 *
 * @param requestId the request that was answered
 * @param decision what was decided: an option id, or any short decision for an approval, or {@code
 *     answered} for free text
 * @param text free text that goes with the decision, at most {@link InputLimits#TEXT_MAX_BYTES}
 *     bytes
 * @param data structured data, at most {@link InputLimits#DATA_MAX_BYTES} bytes as JSON
 * @param responder who answered
 * @param rationale why, when the responder says; at most {@link InputLimits#TEXT_MAX_BYTES} bytes
 * @param confidence how sure the responder is, from 0 to 1, when it says
 * @param answeredAt when the answer was accepted
 * @since 0.1.0-alpha.4
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores an unmodifiable copy")
public record InputResponse(
    String requestId,
    String decision,
    @Nullable String text,
    Map<String, Object> data,
    Responder responder,
    @Nullable String rationale,
    @Nullable Double confidence,
    Instant answeredAt) {

  /**
   * Checks the parts and keeps an immutable copy of the data.
   *
   * @param requestId the request id
   * @param decision the decision
   * @param text free text, or {@code null}
   * @param data structured data
   * @param responder who answered
   * @param rationale why, or {@code null}
   * @param confidence from 0 to 1, or {@code null}
   * @param answeredAt when the answer was accepted
   */
  public InputResponse {
    InputLimits.requireId("requestId", requestId);
    InputLimits.requireId("decision", decision);
    InputLimits.optionalText("text", text, InputLimits.TEXT_MAX_BYTES);
    // Map.copyOf rejects null values, and answer data may hold them
    data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    InputLimits.requireWithin("data", data, InputLimits.DATA_MAX_BYTES);
    Objects.requireNonNull(responder, "responder");
    InputLimits.optionalText("rationale", rationale, InputLimits.TEXT_MAX_BYTES);
    InputLimits.requireConfidence(confidence);
    Objects.requireNonNull(answeredAt, "answeredAt");
  }
}
