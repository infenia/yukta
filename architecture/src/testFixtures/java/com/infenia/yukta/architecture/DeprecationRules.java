// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.Map;

/** Deprecated API is an upgrade waiting to break; the few places that must use it are listed. */
public final class DeprecationRules {

  private DeprecationRules() {}

  /**
   * Fails if a class uses deprecated API, except the listed classes.
   *
   * @param classes the imported classes
   * @param exemptions class name (nested classes count as their outer class) to the reason it may
   *     use deprecated API; keep this short
   */
  @SuppressWarnings("PMD.LooseCoupling")
  public static void check(final JavaClasses classes, final Map<String, String> exemptions) {
    GeneralCodingRules.DEPRECATED_API_SHOULD_NOT_BE_USED
        .because("deprecated calls break on the next upgrade; exemptions: " + exemptions.keySet())
        .allowEmptyShould(true)
        .check(
            classes.that(
                DescribedPredicate.describe(
                    "are not exempt",
                    (JavaClass c) -> !exemptions.containsKey(c.getName().split("\\$")[0]))));
  }
}
