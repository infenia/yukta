// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Short forms of {@link PreparedWorkflowCache} for tests: the shapes its extra constructors used to
 * offer.
 */
public final class PreparedWorkflowCacheFixture {

  private PreparedWorkflowCacheFixture() {}

  /** A PreparedWorkflowCache with the default for what the parameters leave out. */
  public static PreparedWorkflowCache create(final long ttlMs) {
    return new PreparedWorkflowCache(ttlMs, 1_000, new SimpleMeterRegistry());
  }
}
