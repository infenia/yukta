// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.architecture;

import com.infenia.yukta.architecture.DeprecationRules;
import com.infenia.yukta.architecture.ReactiveRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Map;

/** Rules about the code inside the Plus modules; they run over the Plus classes only. */
public final class PlusCodeRules {

  /** Classes that may block, with the reason; every entry parks a thread, so keep this short. */
  private static final Map<String, String> MAY_BLOCK =
      Map.of(
          "com.infenia.yukta.persistence.postgres.R2dbcExecutionLogStore",
          "the store interface is blocking; it runs on its own writer thread, not a reactive one",
          "com.infenia.workers.WorkerAgent",
          "polls for running attempts while draining, on the agent's own thread");

  /** Classes that use deprecated API, with the reason. */
  private static final Map<String, String> MAY_USE_DEPRECATED =
      Map.of(
          "com.infenia.workers.WorkerTransport",
          "connects eagerly with RSocketRequester.Builder.connect/connectTcp (deprecated since"
              + " 5.3); the lazy replacements change how a failed attempt is retried, so moving"
              + " needs the workers acceptance tests");

  @ArchTest
  public static final ArchRule NO_BLOCKING_CALLS = ReactiveRules.noBlockingCalls(MAY_BLOCK);

  /**
   * No class uses deprecated API, except the listed ones.
   *
   * @param classes the imported classes
   */
  @ArchTest
  @SuppressWarnings("PMD.LooseCoupling")
  public static void noDeprecatedApi(final JavaClasses classes) {
    DeprecationRules.check(classes, MAY_USE_DEPRECATED);
  }

  private PlusCodeRules() {}
}
