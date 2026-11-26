// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import com.infenia.yukta.model.control.OnLimit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * How runs of a workflow are admitted, on top of the server's limits.
 *
 * @param concurrency at most so many runs at once per key, or null for no key limit
 */
@Schema(description = "How runs of this workflow are admitted, on top of the server's limits")
public record WorkflowAdmission(
    @Schema(description = "At most so many runs at once per key", nullable = true) @Valid
        @Nullable Concurrency concurrency) {

  /**
   * At most {@code limit} runs at once that share a key, for example one deploy per environment.
   *
   * @param key an expression over the start payload, such as {@code ${payload.customerId}}; a start
   *     whose key resolves to nothing is refused
   * @param limit how many runs with the same key may run at once
   * @param overflow what a start does when its key is at the limit: wait ({@code QUEUE}, the
   *     default) or be refused ({@code REJECT})
   */
  @Schema(description = "At most `limit` runs at once that share a key")
  public record Concurrency(
      @Schema(
              description = "Expression over the start payload that names the key",
              example = "${payload.customerId}")
          @NotBlank(message = "admission.concurrency.key is mandatory")
          @Size(max = 500, message = "admission.concurrency.key is at most 500 characters")
          String key,
      @Schema(description = "Runs with the same key that may run at once", example = "1")
          @Min(value = 1, message = "admission.concurrency.limit must be at least 1")
          @Max(value = 10_000, message = "admission.concurrency.limit must be at most 10000")
          int limit,
      @Schema(
              description = "What a start does when its key is at the limit (default QUEUE)",
              nullable = true)
          @Nullable OnLimit overflow) {

    /** Fills in the default overflow. */
    public Concurrency {
      overflow = overflow != null ? overflow : OnLimit.QUEUE;
    }
  }
}
