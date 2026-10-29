// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.time.Instant;
import lombok.experimental.UtilityClass;

/** Builds the ids of definition versions. */
@UtilityClass
public class DefinitionRevisions {

  /**
   * Builds the id of a version: the save time (so ids sort in save order) and a short content hash.
   *
   * @param savedAt when the version was saved
   * @param definition the saved definition
   * @return the id, safe to use as a file name
   */
  public String idOf(final Instant savedAt, final WorkflowDefinition definition) {
    return String.format("%013d-%08x", savedAt.toEpochMilli(), definition.hashCode());
  }
}
