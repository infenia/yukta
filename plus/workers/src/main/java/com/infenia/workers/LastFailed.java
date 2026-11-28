// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** The worker that failed the last attempt of each node, so that a retry goes to another one. */
final class LastFailed {

  /* default */ void failed(final String executionId, final String nodeId, final String workerId) {
    throw new UnsupportedOperationException("not implemented");
  }

  /* default */ void succeeded(final String executionId, final String nodeId) {
    throw new UnsupportedOperationException("not implemented");
  }

  /* default */ @Nullable String workerOf(final String executionId, final String nodeId) {
    throw new UnsupportedOperationException("not implemented");
  }

  /* default */ static <T> List<T> avoiding(
      final List<T> ordered, final Function<T, String> idOf, @Nullable final String avoid) {
    throw new UnsupportedOperationException("not implemented");
  }
}
