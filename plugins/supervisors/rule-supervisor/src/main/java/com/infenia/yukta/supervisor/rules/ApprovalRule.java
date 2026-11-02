// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.ApprovalNeed;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.supervisor.rules.RuleSet.Deny;
import com.infenia.yukta.supervisor.rules.RuleSet.RequireApproval;
import java.util.Locale;
import java.util.Map;

/**
 * A rule that lets commands run only once someone approves them. It matches commands as a {@link
 * DenyRule} does; a condition that cannot be evaluated holds, so a faulty rule asks for approval
 * rather than letting the command through.
 */
public final class ApprovalRule {

  /** The rule's id. */
  private final String ruleId;

  /** Which commands it matches. */
  private final DenyRule matcher;

  /** What approval it asks for. */
  @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
  private final ApprovalNeed need;

  /** Why, or {@code null} for the default. */
  private final String reason;

  /**
   * Validates and prepares a rule, so mistakes surface when the rules are loaded.
   *
   * @param ruleId the rule's id
   * @param gate what it gates
   * @throws IllegalArgumentException if no command is named, the condition is invalid, the risk is
   *     not {@code LOW}, {@code MEDIUM} or {@code HIGH}, or the route is not a valid name
   */
  public ApprovalRule(final String ruleId, final RequireApproval gate) {
    this.ruleId = ruleId;
    this.matcher = new DenyRule(ruleId, new Deny(gate.command(), gate.condition(), gate.reason()));
    this.need = need(ruleId, gate);
    this.reason = gate.reason();
  }

  private static ApprovalNeed need(final String ruleId, final RequireApproval gate) {
    final String named = gate.risk();
    final InputRisk risk;
    try {
      risk = named == null ? InputRisk.HIGH : InputRisk.valueOf(named.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Rule '" + ruleId + "': risk must be LOW, MEDIUM or HIGH, not '" + named + "'", e);
    }
    try {
      return new ApprovalNeed(risk, gate.route(), null);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("Rule '" + ruleId + "': " + e.getMessage(), e);
    }
  }

  /**
   * Whether the rule gates a command.
   *
   * @param commandName the command's class name
   * @param fields the fields of the command
   * @return true if the command needs approval
   */
  public boolean applies(final String commandName, final Map<String, Object> fields) {
    return matcher.matchesName(commandName) && matcher.applies(fields);
  }

  /**
   * The decision of the rule.
   *
   * @return a decision that requires approval
   */
  public Decision decision() {
    return Decision.requireApproval(
        reason == null ? "approval required by rule " + ruleId : reason, need);
  }
}
