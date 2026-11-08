// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/** Keeps only the classes of the Plus modules (the plus directory and its nested modules). */
public final class PlusOnly implements ImportOption {
  @Override
  public boolean includes(final Location location) {
    return location.contains("/plus/") && location.contains("/build/");
  }
}
