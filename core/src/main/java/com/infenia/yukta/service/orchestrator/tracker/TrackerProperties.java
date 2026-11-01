// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the task tracker ({@code yukta.tracker}).
 *
 * @param sessionTtl how long an idle session is kept; unset keeps every session
 * @param maxSessions most sessions kept in memory, 0 for all
 * @param cleanupTtl how long execution data is kept after its terminal status
 */
@Validated
@ConfigurationProperties(prefix = "yukta.tracker")
public record TrackerProperties(
    @Nullable Duration sessionTtl,
    @Min(0) @DefaultValue("0") int maxSessions,
    @NotNull @DefaultValue("10m") Duration cleanupTtl) {}
