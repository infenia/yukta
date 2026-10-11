// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.supervisor.rules.RuleSet.Deny;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;

/** A rule that refuses commands. */
@Slf4j
public final class DenyRule {

  /** Suffix that command names may omit. */
  private static final String SUFFIX = "Command";

  /** The rule's id. */
  private final String ruleId;

  /** What it refuses. */
  private final Deny deny;

  /** The parsed condition, or {@code null}. */
  private final Expression condition;

  /**
   * Validates and prepares a rule, so mistakes surface when the rules are loaded.
   *
   * @param ruleId the rule's id
   * @param deny what it refuses
   * @throws IllegalArgumentException if no command is named or the condition is invalid
   */
  public DenyRule(final String ruleId, final Deny deny) {
    this.ruleId = ruleId;
    this.deny = deny;
    if (deny.command() == null || deny.command().isBlank()) {
      throw new IllegalArgumentException("Rule '" + ruleId + "' must name the command to deny");
    }
    this.condition = deny.condition() == null ? null : Conditions.parse(ruleId, deny.condition());
  }

  /**
   * Whether the rule is about a kind of command.
   *
   * @param commandName the command's class name, for example {@code StopWorkflowCommand}
   * @return true if the names are equal, ignoring case and the {@code Command} suffix
   */
  public boolean matchesName(final String commandName) {
    return strip(deny.command()).equalsIgnoreCase(strip(commandName));
  }

  /**
   * Whether the rule's condition holds for a command. A condition that cannot be evaluated holds,
   * so a faulty rule never lets a forbidden command through.
   *
   * @param fields the fields of the command
   * @return true if the command is refused
   */
  public boolean applies(final Map<String, Object> fields) {
    boolean applies = true;
    if (condition != null) {
      try {
        applies = Conditions.holds(condition, fields);
      } catch (final EvaluationException e) {
        log.atWarn().setCause(e).addKeyValue("rule", ruleId).log("Condition failed; denying");
      }
    }
    return applies;
  }

  /**
   * Why the command is refused.
   *
   * @return the rule's reason, or a default naming the rule
   */
  public String reason() {
    return deny.reason() == null ? "denied by rule " + ruleId : deny.reason();
  }

  private static String strip(final String name) {
    return name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name;
  }
}
