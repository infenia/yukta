// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * A configured supervisor as shown. Secret settings are shown as {@code ***}.
 *
 * @param id the supervisor id
 * @param type the supervisor type
 * @param description what it is for, or {@code null}
 * @param settings the settings, secrets hidden
 * @param dryRun whether its commands are only recorded
 * @param system true for a supervisor configured as a bean or property; it is read-only
 * @param attachments how many attachments run it
 * @param applied commands applied since startup, over its attachments
 * @param rejected commands refused since startup
 * @param recorded commands only recorded because of dry-run
 * @param rateLimited commands dropped by the rate limit
 */
@Schema(description = "A configured supervisor")
public record SupervisorView(
    String id,
    String type,
    String description,
    Map<String, Object> settings,
    boolean dryRun,
    @Schema(description = "True for a bean or property supervisor, which is read-only")
        boolean system,
    int attachments,
    long applied,
    long rejected,
    long recorded,
    long rateLimited,
    @Schema(description = "The supervisors it reports to, in the order they are asked")
        List<String> parents,
    @Schema(
            description =
                "How each kind of delegation (APPROVAL, INPUT, CONSULT) is asked of the parents")
        Map<String, DelegationView> delegation) {

  /**
   * How one kind of delegation is asked.
   *
   * @param mode ORDERED, ALL or ANY
   * @param timeoutSeconds how long one parent has to answer
   */
  public record DelegationView(String mode, long timeoutSeconds) {}

  /** Keeps an immutable copy of the settings. */
  public SupervisorView {
    settings = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(settings));
    parents = parents == null ? List.of() : List.copyOf(parents);
    delegation = delegation == null ? Map.of() : Map.copyOf(delegation);
  }
}
