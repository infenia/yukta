// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.streaming;

import com.infenia.yukta.config.YuktaProperties;

/**
 * Short forms of {@link DefaultStatusHistoryCache} for tests: the shapes its extra constructors
 * used to offer.
 */
public final class DefaultStatusHistoryCacheFixture {

  private DefaultStatusHistoryCacheFixture() {}

  /** A DefaultStatusHistoryCache with the default for what the parameters leave out. */
  public static DefaultStatusHistoryCache create(final int ttlMinutes) {
    return new DefaultStatusHistoryCache(ttlMinutes, 200, 1000);
  }

  /** A DefaultStatusHistoryCache with the default for what the parameters leave out. */
  public static DefaultStatusHistoryCache create(final YuktaProperties properties) {
    return new DefaultStatusHistoryCache(properties);
  }
}
