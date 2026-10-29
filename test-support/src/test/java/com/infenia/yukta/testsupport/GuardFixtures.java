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
  "PMD.CommentDefaultAccessModifier",
  "PMD.UncommentedEmptyMethodBody",
  "PMD.MissingStaticMethodInNonInstantiatableClass"
})
class GuardFixtures {

  static class Plain {
    void plain() {}

    @ExpectedErrorLog("went wrong")
    void expecting() {}

    @ExpectedErrorLog(sometimes = "timing dependent")
    void sometimes() {}
  }

  @ExpectedErrorLog("went wrong")
  static class Outer {
    void plain() {}

    static class Inner {
      void plain() {}
    }
  }

  @ExpectedErrorLog("never happens")
  static class Unused {
    void plain() {}
  }

  @ExpectedErrorLog(sometimes = "never happens either")
  static class Sometimes {
    void plain() {}
  }
}
