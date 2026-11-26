// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.architecture;

import com.infenia.yukta.architecture.ReactiveRules;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.Map;

/** Rules about the code inside the Plus modules; they run over the Plus classes only. */
public final class PlusCodeRules {

  /** Classes that may block, with the reason; every entry parks a thread, so keep this short. */
  private static final Map<String, String> MAY_BLOCK =
      Map.of(
          "com.infenia.workers.WorkerAgent",
          "polls for running attempts while draining, on the agent's own thread");

  @ArchTest
  public static final ArchRule NO_BLOCKING_CALLS = ReactiveRules.noBlockingCalls(MAY_BLOCK);

  /** No class uses deprecated API. */
  @ArchTest
  public static final ArchRule NO_DEPRECATED_API =
      GeneralCodingRules.DEPRECATED_API_SHOULD_NOT_BE_USED
          .because("deprecated calls break on the next upgrade")
          .allowEmptyShould(true);

  private PlusCodeRules() {}
}
