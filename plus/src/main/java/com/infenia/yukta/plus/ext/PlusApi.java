// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.ext;

import lombok.experimental.UtilityClass;

/** The version of the extension API an {@link EnterpriseModule} is built against. */
@UtilityClass
public class PlusApi {

  /**
   * Version of the extension API. It grows when {@link EnterpriseModule}, {@link LicenseVerifier}
   * or the contract tests change in a way that needs a module to be rebuilt.
   */
  public static final int VERSION = 1;
}
