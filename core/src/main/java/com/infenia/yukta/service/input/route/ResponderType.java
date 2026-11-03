// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.route;

/** Who a tier is meant for. */
public enum ResponderType {
  /** Supervisors and other programs. A request that needs a person skips such a tier. */
  AUTO,
  /** A person, or something a person answers through. */
  HUMAN
}
