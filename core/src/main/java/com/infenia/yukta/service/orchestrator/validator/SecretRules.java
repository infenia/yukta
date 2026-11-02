// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.workflow.ConfigRedactor;
import java.util.ArrayList;
import java.util.List;

/** The eager check that a definition holds no secret mask and no literal secret. */
final class SecretRules {

  private SecretRules() {}

  /** A secret mask or a literal secret in the definition: every problem found. */
  /* default */ static List<String> findProblems(
      final WorkflowDefinition def, final PluginRegistry registry) {
    final List<String> problems = new ArrayList<>();
    final List<String> masked = ConfigRedactor.placeholders(def);
    if (!masked.isEmpty()) {
      problems.add(
          "The definition holds the secret mask '"
              + ConfigRedactor.PLACEHOLDER
              + "' at "
              + String.join(", ", masked)
              + ". The read APIs mask literal secrets; replace each with the real value or a"
              + " decrypted:<key> reference before saving, or the stored secret would be"
              + " lost");
    }
    final List<String> literals = new ConfigRedactor(registry).literalSecrets(def);
    if (!literals.isEmpty()) {
      problems.add(
          "A workflow definition must not hold secrets, but "
              + String.join(", ", literals)
              + " is a literal value. Store the secret as YUKTA_SECRET_<KEY> and write"
              + " decrypted:<key> (or a ${...} variable) here instead");
    }
    return problems;
  }
}
