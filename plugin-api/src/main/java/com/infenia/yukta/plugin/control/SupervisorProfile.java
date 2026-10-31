// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A reusable supervisor configuration: which kind of supervisor, how it is set up, and whether its
 * commands are only recorded. A {@link SupervisorBinding} attaches it to a scope.
 *
 * @param id the profile id, unique
 * @param type the supervisor kind, matching a {@link SupervisorFactory#type()}
 * @param settings the settings the factory reads; may hold secrets, which APIs must not echo
 * @param dryRun whether the commands of supervisors made from this profile are only recorded
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores an unmodifiable copy")
public record SupervisorProfile(
    String id, String type, Map<String, Object> settings, boolean dryRun) {

  /** Rejects missing ids and keeps an immutable copy of the settings. */
  public SupervisorProfile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    // Map.copyOf rejects null values, and a setting may be null
    settings =
        settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
  }
}
