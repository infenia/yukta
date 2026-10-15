// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import java.time.Instant;
import org.springframework.lang.Nullable;

/**
 * One saved version of a workflow definition.
 *
 * @param id identifies the version; ids sort in the order the versions were saved
 * @param savedAt when it was saved
 * @param note what the version is for, or null when nobody wrote one
 */
@SuppressWarnings("PMD.ShortVariable")
public record DefinitionRevision(String id, Instant savedAt, @Nullable String note) {

  /**
   * A version without a note.
   *
   * @param id identifies the version
   * @param savedAt when it was saved
   */
  public DefinitionRevision(final String id, final Instant savedAt) {
    this(id, savedAt, null);
  }

  /**
   * The same version with another note.
   *
   * @param newNote the note, or null for none
   * @return the version with the note
   */
  public DefinitionRevision withNote(@Nullable final String newNote) {
    return new DefinitionRevision(id, savedAt, newNote);
  }
}
