// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.architecture;

import com.infenia.yukta.architecture.Scopes;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** Runs the rules about the border between Plus and the Apache modules over every module. */
@AnalyzeClasses(
    packages = {"com.infenia.yukta", "com.infenia.workers"},
    importOptions = {ImportOption.DoNotIncludeTests.class, Scopes.WithoutTestSupport.class})
@SuppressWarnings("PMD.TestClassWithoutTestCases")
class PlusBoundaryTest {

  @ArchTest /* default */ static final ArchTests BOUNDARY = ArchTests.in(PlusRules.class);
}
