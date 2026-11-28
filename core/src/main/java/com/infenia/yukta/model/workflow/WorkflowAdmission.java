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
 * @param throttle at most so many starts per period per key, or null for none
 * @param debounce one run per key and period, or null for none
 */
@Schema(description = "How runs of this workflow are admitted, on top of the server's limits")
public record WorkflowAdmission(
    @Schema(description = "At most so many runs at once per key", nullable = true) @Valid
        @Nullable Concurrency concurrency,
    @Schema(description = "At most so many starts per period per key", nullable = true) @Valid
        @Nullable Throttle throttle,
    @Schema(description = "One run per key and period; later starts get its id", nullable = true)
        @Valid
        @Nullable Debounce debounce) {

  /** Longest period a throttle or debounce may have: one day. */
  public static final long MAX_PERIOD_MS = 86_400_000L;

  /**
   * At most {@code limit} starts per {@code periodMs} that share a key; a start over it waits as
   * {@code QUEUED} until the period has room again.
   *
   * @param key an expression over the start payload, or null for one key for the whole workflow
   * @param limit how many starts with the same key may be admitted per period
   * @param periodMs the period, in milliseconds
   */
  @Schema(description = "At most `limit` starts per `periodMs` that share a key; more wait")
  public record Throttle(
      @Schema(
              description = "Expression over the start payload; none for the whole workflow",
              nullable = true)
          @Size(max = 500, message = "admission.throttle.key is at most 500 characters")
          @Nullable String key,
      @Schema(description = "Starts with the same key per period", example = "10")
          @Min(value = 1, message = "admission.throttle.limit must be at least 1")
          @Max(value = 10_000, message = "admission.throttle.limit must be at most 10000")
          int limit,
      @Schema(description = "The period in milliseconds", example = "60000")
          @Min(value = 1, message = "admission.throttle.periodMs must be at least 1")
          @Max(value = MAX_PERIOD_MS, message = "admission.throttle.periodMs is at most one day")
          long periodMs) {}

  /**
   * The first start with a key runs; starts with the same key within {@code periodMs} of it start
   * nothing and get its execution id, so a burst of starts becomes one run.
   *
   * @param key an expression over the start payload, or null for one key for the whole workflow
   * @param periodMs how long after a run's start later starts are folded into it, in milliseconds
   */
  @Schema(description = "Starts within `periodMs` of the first with the same key get its id")
  public record Debounce(
      @Schema(
              description = "Expression over the start payload; none for the whole workflow",
              nullable = true)
          @Size(max = 500, message = "admission.debounce.key is at most 500 characters")
          @Nullable String key,
      @Schema(description = "The window in milliseconds", example = "5000")
          @Min(value = 1, message = "admission.debounce.periodMs must be at least 1")
          @Max(value = MAX_PERIOD_MS, message = "admission.debounce.periodMs is at most one day")
          long periodMs) {}

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
