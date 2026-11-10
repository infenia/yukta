// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** Runs the control entry rules on every module's classes. */
@AnalyzeClasses(
    packages = "com.infenia.yukta",
    importOptions = ImportOption.DoNotIncludeTests.class)
@SuppressWarnings("PMD.TestClassWithoutTestCases")
class ControlEntryTest {

  @ArchTest /* default */ static final ArchTests RULES = ArchTests.in(ControlEntryRules.class);
}
