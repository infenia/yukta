// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import java.time.Instant;

/**
 * One saved version of a workflow definition.
 *
 * @param id identifies the version; ids sort in the order the versions were saved
 * @param savedAt when it was saved
 */
@SuppressWarnings("PMD.ShortVariable")
public record DefinitionRevision(String id, Instant savedAt) {}
