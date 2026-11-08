// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture.fixture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Has a hand-written logger; the self-test expects the rule to object. */
public final class BadLogger {
  /** Wrong shape: public and not named log. */
  public static final Logger LOGGER = LoggerFactory.getLogger(BadLogger.class);

  private BadLogger() {}
}
