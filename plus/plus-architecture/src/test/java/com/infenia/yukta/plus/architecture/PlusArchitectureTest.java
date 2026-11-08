// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.architecture;

import com.infenia.yukta.architecture.HygieneRules;
import com.infenia.yukta.architecture.StructureRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** Runs the Plus rules, and the coding rules of every module, over the Plus classes. */
@AnalyzeClasses(
    packages = {"com.infenia.yukta", "com.infenia.workers"},
    importOptions = {ImportOption.DoNotIncludeTests.class, PlusOnly.class})
@SuppressWarnings("PMD.TestClassWithoutTestCases")
class PlusArchitectureTest {

  @ArchTest /* default */ static final ArchTests CODE = ArchTests.in(PlusCodeRules.class);

  @ArchTest /* default */ static final ArchTests HYGIENE = ArchTests.in(HygieneRules.class);

  @ArchTest /* default */ static final ArchTests STRUCTURE = ArchTests.in(StructureRules.class);
}
