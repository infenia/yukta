// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.route;

/** How the responders of a tier decide. */
public enum Mode {
  /** The first valid answer wins. */
  FIRST,
  /** The supervisors of the tier vote; {@code quorum} equal decisions win. */
  QUORUM,
  /** The supervisors of the tier vote; all that are eligible must agree. */
  UNANIMOUS
}
