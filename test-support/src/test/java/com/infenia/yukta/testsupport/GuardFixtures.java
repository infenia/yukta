// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import lombok.experimental.UtilityClass;

/**
 * Test classes for ErrorGuardExtensionTest; kept apart so the annotations on that test do not
 * apply.
 */
@UtilityClass
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.CommentDefaultAccessModifier",
  "PMD.UncommentedEmptyMethodBody",
  "PMD.MissingStaticMethodInNonInstantiatableClass",
  "PMD.AtLeastOneConstructor"
})
class GuardFixtures {

  static class Plain {
    void plain() {}

    @ExpectedErrorLog("went wrong")
    void expecting() {}
  }

  @ExpectedErrorLog("went wrong")
  static class Outer {
    static class Inner {
      void plain() {}
    }
  }
}
