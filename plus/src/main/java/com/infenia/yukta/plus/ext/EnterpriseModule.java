// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.ext;

import java.util.List;

/**
 * A module that extends the Plus edition, such as the Enterprise modules. Register one as a Spring
 * bean; the {@link EnterpriseModuleRegistry} checks it against {@link PlusApi#VERSION}, lists its
 * features and makes the build report the Enterprise edition. The module replaces the single-server
 * defaults of {@code core} (stores, election, authenticator) by providing beans of the same types,
 * and should pass the contract tests of {@code testFixtures(project(":plus"))}.
 */
public interface EnterpriseModule {

  /**
   * The module id, such as {@code persistence-postgres}.
   *
   * @return a short unique name
   */
  String moduleId();

  /**
   * The module version.
   *
   * @return its version text
   */
  String version();

  /**
   * The {@link PlusApi#VERSION} the module was built against.
   *
   * @return the API version
   */
  int requiresPlusApi();

  /**
   * The features the module adds, shown by {@code GET /api/edition}.
   *
   * @return feature names
   */
  default List<String> features() {
    return List.of();
  }
}
