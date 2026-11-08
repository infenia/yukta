// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** Runs the cross-module rules and the coding rules over every Apache module. */
@AnalyzeClasses(
    packages = "com.infenia.yukta",
    importOptions = {ImportOption.DoNotIncludeTests.class, Scopes.WithoutTestSupport.class})
@SuppressWarnings("PMD.TestClassWithoutTestCases")
class ModuleArchitectureTest {

  @ArchTest /* default */ static final ArchTests MODULES = ArchTests.in(ModuleRules.class);

  @ArchTest /* default */ static final ArchTests HYGIENE = ArchTests.in(HygieneRules.class);
}
