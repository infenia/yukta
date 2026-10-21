// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.ext;

import java.util.List;

/**
 * Checks the licence that entitles this server to its {@link EnterpriseModule}s. The Enterprise
 * build provides the real one (offline verification of a signed licence file); without one the
 * registry only warns, because this repository never refuses to run what it contains.
 */
@FunctionalInterface
public interface LicenseVerifier {

  /**
   * Check the licence for the installed modules.
   *
   * @param modules the modules present
   * @return the outcome
   */
  Status verify(List<EnterpriseModule> modules);

  /**
   * The outcome of a licence check.
   *
   * @param valid whether the licence covers the modules
   * @param message what to tell the operator
   */
  record Status(boolean valid, String message) {}

  /**
   * The verifier of a build that has none.
   *
   * @return a verifier that reports that no licence check is installed
   */
  static LicenseVerifier none() {
    return modules -> new Status(false, "no licence verifier is installed");
  }
}
