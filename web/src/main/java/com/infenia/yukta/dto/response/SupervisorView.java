// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
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
    long rateLimited) {}
