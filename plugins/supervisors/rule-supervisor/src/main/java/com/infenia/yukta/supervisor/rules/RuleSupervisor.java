// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.supervisor.rules.RuleSet.Deny;
import com.infenia.yukta.supervisor.rules.RuleSet.Rule;
import com.infenia.yukta.supervisor.rules.RuleSet.Then;
import com.infenia.yukta.supervisor.rules.RuleSet.When;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * An {@link ExecutionSupervisor} driven by declarative rules from a JSON file; see {@link RuleSet}.
 * Enabled by setting {@code yukta.supervisor.rules.file}.
 *
 * <p>A condition that cannot be evaluated does not match an event rule and denies the command of a
 * deny rule, so a faulty rule never lets a forbidden command through.
 */
@Component
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.ExcessiveImports"})
@ConditionalOnProperty("yukta.supervisor.rules.file")
public final class RuleSupervisor implements ExecutionSupervisor {

  /** Identifies this supervisor in the audit log. */
  public static final String SUPERVISOR_ID = "rules";

  /** A rule that counts nothing triggers on the first match. */
  private static final int NOT_COUNTING = 1;

  /** How many restarted runs are remembered for {@code attempt}. */
  private static final int MAX_TRACKED_RUNS = 1000;

  /** Reads rules strictly, so a misspelled field fails at startup instead of being ignored. */
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** The rules that react to events. */
  private final List<EventRule> eventRules = new ArrayList<>();

  /** The rules that refuse commands. */
  private final List<DenyRule> denyRules = new ArrayList<>();

  /** Recent matches of counting rules, by rule and execution. */
  private final Map<String, Deque<Instant>> matches = new ConcurrentHashMap<>();

  /** Restarts that led to a run started by a rule, by execution id; the oldest are forgotten. */
  private final Map<String, Integer> attempts =
      Collections.synchronizedMap(
          new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Integer> eldest) {
              return size() > MAX_TRACKED_RUNS;
            }
          });

  /** Time source. */
  private final Clock clock;

  /**
   * Loads the rules from a file.
   *
   * @param file the JSON rules file
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if a rule is invalid
   */
  @Autowired
  public RuleSupervisor(@Value("${yukta.supervisor.rules.file}") final Path file)
      throws IOException {
    this(read(Files.readString(file)), Clock.systemUTC());
  }

  /**
   * Creates the supervisor from parsed rules.
   *
   * @param ruleSet the rules
   * @param clock the time source
   * @throws IllegalArgumentException if a rule is invalid
   */
  public RuleSupervisor(final RuleSet ruleSet, final Clock clock) {
    this.clock = clock;
    ruleSet.rules().forEach(this::register);
  }

  @SuppressWarnings("PMD.CyclomaticComplexity")
  private void register(final Rule rule) {
    final String ruleId = rule.id();
    final When when = rule.when();
    final Then then = rule.then();
    final Deny deny = rule.deny();
    if (ruleId == null || ruleId.isBlank()) {
      throw new IllegalArgumentException("Every rule needs an id");
    }
    final boolean reacts = when != null || then != null;
    if (reacts == (deny != null)) {
      throw new IllegalArgumentException(
          "Rule '" + ruleId + "' needs either when and then, or deny");
    }
    if (deny != null) {
      denyRules.add(new DenyRule(ruleId, deny));
    } else if (when == null || then == null) {
      throw new IllegalArgumentException("Rule '" + ruleId + "' needs both when and then");
    } else {
      eventRules.add(new EventRule(ruleId, when, then));
    }
  }

  /**
   * Parses rules from JSON.
   *
   * @param json the rules
   * @return the rules
   * @throws IllegalArgumentException if the JSON is not a valid rule set
   */
  public static RuleSet read(final String json) {
    try {
      return JSON.readValue(json, RuleSet.class);
    } catch (final JacksonException e) {
      throw new IllegalArgumentException("Invalid rules file: " + e.getOriginalMessage(), e);
    }
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return SUPERVISOR_ID;
  }

  @Override
  public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
    return events.concatMapIterable(this::react);
  }

  @Override
  public Mono<Decision> review(final ControlRequest request) {
    final Map<String, Object> fields = Fields.commandFields(request);
    final String name = request.command().getClass().getSimpleName();
    Decision decision = Decision.ALLOW;
    for (final DenyRule rule : denyRules) {
      if (rule.matchesName(name) && rule.applies(fields)) {
        decision = Decision.deny(rule.reason());
        break;
      }
    }
    return Mono.just(decision);
  }

  private List<ExecutionControlCommand> react(final ExecutionEvent event) {
    final List<ExecutionControlCommand> commands = new ArrayList<>();
    for (final EventRule rule : eventRules) {
      final int attempt = attempts.getOrDefault(event.executionId(), 0);
      if (rule.matches(event, attempt) && countReached(rule, event)) {
        final String newExecutionId = UUID.randomUUID().toString();
        if (rule.restarts()) {
          attempts.put(newExecutionId, attempt + 1);
        }
        commands.add(rule.command(event, newExecutionId));
      }
    }
    return commands;
  }

  /** Counts the match and says whether the rule's threshold within its window is reached. */
  private boolean countReached(final EventRule rule, final ExecutionEvent event) {
    final boolean reached;
    if (rule.count() > NOT_COUNTING) {
      final Instant now = clock.instant();
      final Deque<Instant> recent =
          matches.computeIfAbsent(
              rule.ruleId() + '/' + event.executionId(), _ -> new ArrayDeque<>());
      recent.addLast(now);
      final Instant oldest = now.minus(rule.window());
      while (!recent.isEmpty() && recent.peekFirst().isBefore(oldest)) {
        recent.removeFirst();
      }
      reached = recent.size() >= rule.count();
      if (reached) {
        recent.clear();
      }
    } else {
      reached = true;
    }
    return reached;
  }
}
