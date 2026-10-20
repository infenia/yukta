// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.edition;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The editions of Yukta; see LICENSING.md. */
@Getter
@RequiredArgsConstructor
public enum Edition {
  /** The default build, Apache-2.0. */
  COMMUNITY("Community", "Apache-2.0"),
  /** The build with the {@code plus} module, AGPL-3.0-only. */
  PLUS("Plus", "AGPL-3.0-only"),
  /** The build with Enterprise modules, commercial. */
  ENTERPRISE("Enterprise", "commercial licence");

  /** Name shown to people. */
  private final String displayName;

  /** Licence of the edition. */
  private final String license;
}
