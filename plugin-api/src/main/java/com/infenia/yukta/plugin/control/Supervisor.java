// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A configured supervisor: which kind of supervisor, how it is set up, and whether its commands are
 * only recorded. A {@link SupervisorAttachment} attaches it to a scope.
 *
 * @param id the supervisor id, unique
 * @param type the supervisor kind, matching a {@link SupervisorFactory#type()}
 * @param settings the settings the factory reads; may hold secrets, which APIs must not echo
 * @param dryRun whether the commands of the supervisor are only recorded
 * @param description what it is for, or {@code null}
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores an unmodifiable copy")
public record Supervisor(
    String id,
    String type,
    Map<String, Object> settings,
    boolean dryRun,
    @Nullable String description) {

  /**
   * A supervisor without a description.
   *
   * @param id the supervisor id
   * @param type the supervisor kind
   * @param settings the settings
   * @param dryRun whether commands are only recorded
   */
  public Supervisor(
      final String id,
      final String type,
      final Map<String, Object> settings,
      final boolean dryRun) {
    this(id, type, settings, dryRun, null);
  }

  /** Rejects missing ids and keeps an immutable copy of the settings. */
  public Supervisor {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    // Map.copyOf rejects null values, and a setting may be null
    settings =
        settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
  }
}
