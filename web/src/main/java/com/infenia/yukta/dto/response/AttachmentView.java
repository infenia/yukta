// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An attachment of a supervisor to a scope.
 *
 * @param id the attachment id
 * @param supervisorId the supervisor it runs
 * @param scope the scope key
 * @param enabled whether it runs
 * @param source {@code MANUAL}, {@code DEFINITION} or {@code SYSTEM}
 * @param originSessionId for a DEFINITION attachment, the session that declared it
 * @param originWorkflowId for a DEFINITION attachment, the workflow that declared it
 */
@Schema(description = "An attachment of a supervisor to a scope")
public record AttachmentView(
    String id,
    String supervisorId,
    @Schema(
            description =
                "global, session:<id>, workflow:<id>, workflow:<sessionId>/<workflowId>, or a"
                    + " section: a workflow form, then // and node selectors separated by commas",
            example = "workflow:s1/release//build.*,publish")
        String scope,
    boolean enabled,
    @Schema(description = "MANUAL, DEFINITION or SYSTEM") String source,
    String originSessionId,
    String originWorkflowId) {}
