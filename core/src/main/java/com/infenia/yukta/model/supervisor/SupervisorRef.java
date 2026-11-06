// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.supervisor;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A supervisor declared in a session or workflow definition. It is attached when the definition is
 * saved and detached when the declaration goes.
 *
 * @param supervisor the id of a configured supervisor
 * @param nodes for a workflow: the node ids or {@code <prefix>.*} selectors to limit it to (a
 *     section of the workflow); missing or empty means the whole workflow. Not allowed in a session
 * @param enabled whether it runs; true when missing
 */
@Schema(description = "A supervisor declared in a session or workflow definition")
@SuppressWarnings("PMD.NullAssignment") // null is the documented value for "whole workflow"
public record SupervisorRef(
    @Schema(description = "The id of a configured supervisor")
        @NotBlank(message = "Supervisor id is mandatory")
        String supervisor,
    @Schema(
            description =
                "Node ids or <prefix>.* selectors; the supervisor then watches only those nodes."
                    + " Workflow definitions only; missing means the whole workflow")
        @Nullable List<String> nodes,
    @Schema(description = "Whether it runs; true when missing") @Nullable Boolean enabled) {

  /** Keeps an immutable copy of the selectors; an empty list means none. */
  public SupervisorRef {
    nodes = nodes == null || nodes.isEmpty() ? null : List.copyOf(nodes);
  }

  /**
   * Whether it runs.
   *
   * @return false only when {@code enabled} is {@code false}
   */
  public boolean isEnabled() {
    return enabled == null || enabled;
  }
}
