// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** Runs the rules that belong to the Mcp module on its own classes. */
@AnalyzeClasses(
    packages = "com.infenia.yukta",
    importOptions = {ImportOption.DoNotIncludeTests.class, Scopes.OnlyMcp.class})
@SuppressWarnings("PMD.TestClassWithoutTestCases")
class McpArchitectureTest {

  @ArchTest /* default */ static final ArchTests STRUCTURE = ArchTests.in(StructureRules.class);
}
