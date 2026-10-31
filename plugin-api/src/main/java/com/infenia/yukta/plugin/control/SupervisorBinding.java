// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.util.Objects;

/**
 * Attaches a {@link SupervisorProfile} to a scope: the supervisor made from the profile watches and
 * may veto only the runs within the scope.
 *
 * @param id the binding id, unique; it is the supervisor's id in audit entries
 * @param profileId the profile to run
 * @param scope the runs to supervise
 * @param enabled whether the supervisor runs; a disabled binding is kept but not started
 */
public record SupervisorBinding(
    String id, String profileId, SupervisorScope scope, boolean enabled) {

  /** Rejects missing parts. */
  public SupervisorBinding {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(scope, "scope");
  }
}
