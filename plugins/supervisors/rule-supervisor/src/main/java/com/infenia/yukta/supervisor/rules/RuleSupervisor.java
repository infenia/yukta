// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.supervisor.rules.RuleSet.Deny;
import com.infenia.yukta.supervisor.rules.RuleSet.RequireApproval;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
 *
 * <p>A rule can also answer or escalate an input request ({@code then.answer}, {@code
 * then.escalate}). The server decides whether an answer counts: only a supervisor made from a
 * profile with {@code canAnswer} and a high enough {@code maxRisk} may answer, never one configured
 * by file, and never a request of risk {@code HIGH}. Rules that answer without that permission are
 * reported with a warning when the rules are loaded.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.ExcessiveImports", "PMD.TooManyMethods"})
@ConditionalOnProperty("yukta.supervisor.rules.file")
public final class RuleSupervisor implements ExecutionSupervisor {

  /** Identifies this supervisor in the audit log. */
  public static final String SUPERVISOR_ID = "rules";

  /** A rule has exactly one action. */
  private static final int ONE_ACTION = 1;

  /** A rule that counts nothing triggers on the first match. */
  private static final int NOT_COUNTING = 1;

  /** How many restarted runs are remembered for {@code attempt}. */
  private static final int MAX_TRACKED_RUNS = 1000;

  /**
   * What a supervisor configured by file may do: every command, but never answer an input request
   * (only a supervisor made from a profile can be granted that).
   */
  public static final SupervisorCapabilities FILE_CAPABILITIES =
      new SupervisorCapabilities(null, false, InputRisk.LOW);

  /** Profile settings that are capabilities for the host, not rules. */
  private static final Set<String> CAPABILITY_KEYS =
      Set.of(
          SupervisorCapabilities.ALLOWED_COMMANDS,
          SupervisorCapabilities.CAN_ANSWER,
          SupervisorCapabilities.MAX_RISK);

  /** Writes profile settings back to the JSON the rule reader takes. */
  private static final JsonMapper PLAIN_JSON = JsonMapper.builder().build();

  /** Reads rules strictly, so a misspelled field fails at startup instead of being ignored. */
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** The rules that react to events. */
  private final List<EventRule> eventRules = new ArrayList<>();

  /** The rules that refuse commands. */
  private final List<DenyRule> denyRules = new ArrayList<>();

  /** The rules that let commands run only once approved. */
  private final List<ApprovalRule> approvalRules = new ArrayList<>();

  /** Recent matches of counting rules, by rule and execution. */
  private final Map<String, Deque<Instant>> matches = boundedMap();

  /** Restarts that led to a run started by a rule, by execution id; the oldest are forgotten. */
  private final Map<String, Integer> attempts = boundedMap();

  /** Time source. */
  private final Clock clock;

  /** Why some rules will be denied, or {@code null} when every rule may act. */
  private final @Nullable String loadWarning;

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
    this(ruleSet, clock, FILE_CAPABILITIES);
  }

  /**
   * Creates the supervisor from parsed rules, knowing what its profile allows, so that rules the
   * server would deny are reported when they are loaded.
   *
   * @param ruleSet the rules
   * @param clock the time source
   * @param capabilities what the supervisor's profile allows; {@link #FILE_CAPABILITIES} for one
   *     configured by file
   * @throws IllegalArgumentException if a rule is invalid
   */
  public RuleSupervisor(
      final RuleSet ruleSet, final Clock clock, final SupervisorCapabilities capabilities) {
    this.clock = clock;
    ruleSet.rules().forEach(this::register);
    this.loadWarning = answerWarning(eventRules, capabilities).orElse(null);
    if (loadWarning != null) {
      log.atWarn().log(loadWarning);
    }
  }

  /**
   * The warning for rules that answer input requests on a supervisor that may not answer.
   *
   * @param rules the event rules
   * @param capabilities what the supervisor may do
   * @return the warning, naming the rules, or empty when there is nothing to warn about
   */
  /* default */ static Optional<String> answerWarning(
      final List<EventRule> rules, final SupervisorCapabilities capabilities) {
    final List<String> answering =
        rules.stream().filter(EventRule::answers).map(EventRule::ruleId).toList();
    return answering.isEmpty() || capabilities.canAnswer()
        ? Optional.empty()
        : Optional.of(
            "Rules "
                + answering
                + " answer input requests, but this supervisor may not answer (canAnswer is off,"
                + " or it is configured by file): the server will deny their answers. Attach the"
                + " rules as a profile with canAnswer: true and a maxRisk, or use escalate");
  }

  /**
   * Why some rules will be denied when they act.
   *
   * @return the warning logged at load time, or empty
   */
  public Optional<String> warning() {
    return Optional.ofNullable(loadWarning);
  }

  @SuppressWarnings("PMD.CyclomaticComplexity")
  private void register(final Rule rule) {
    final String ruleId = rule.id();
    final When when = rule.when();
    final Then then = rule.then();
    final Deny deny = rule.deny();
    final RequireApproval gate = rule.requireApproval();
    if (ruleId == null || ruleId.isBlank()) {
      throw new IllegalArgumentException("Every rule needs an id");
    }
    final boolean reacts = when != null || then != null;
    final int kinds = (reacts ? 1 : 0) + (deny == null ? 0 : 1) + (gate == null ? 0 : 1);
    if (kinds != ONE_ACTION) {
      throw new IllegalArgumentException(
          "Rule '" + ruleId + "' needs either when and then, deny, or requireApproval");
    }
    if (gate != null) {
      approvalRules.add(new ApprovalRule(ruleId, gate));
    } else if (deny != null) {
      denyRules.add(new DenyRule(ruleId, deny));
    } else if (when == null || then == null) {
      throw new IllegalArgumentException("Rule '" + ruleId + "' needs both when and then");
    } else {
      eventRules.add(new EventRule(ruleId, when, then));
    }
  }

  /**
   * Parses rules from the settings of a profile. The capability settings ({@code allowedCommands},
   * {@code canAnswer}, {@code maxRisk}) are for the host and are left out; every other key is read
   * as strictly as a rules file.
   *
   * @param settings the profile's settings: {@code {"rules": [...]}} plus capabilities
   * @return the rules
   * @throws IllegalArgumentException if the settings are not a valid rule set
   */
  @SuppressWarnings("PMD.UseConcurrentHashMap") // a local copy that keeps the order
  public static RuleSet read(final Map<String, Object> settings) {
    final Map<String, Object> rules = new LinkedHashMap<>(settings);
    rules.keySet().removeAll(CAPABILITY_KEYS);
    // the settings came from JSON, so writing them back cannot fail; reading them can
    return read(PLAIN_JSON.writeValueAsString(rules));
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
    for (final DenyRule rule : denyRules) {
      if (rule.matchesName(name) && rule.applies(fields)) {
        return Mono.just(Decision.deny(rule.reason()));
      }
    }
    // a denial wins; otherwise the first approval rule that matches
    return Mono.just(
        approvalRules.stream()
            .filter(rule -> rule.applies(name, fields))
            .findFirst()
            .map(ApprovalRule::decision)
            .orElse(Decision.ALLOW));
  }

  /** A map that forgets its oldest entries beyond {@link #MAX_TRACKED_RUNS}. */
  private static <V> Map<String, V> boundedMap() {
    return Collections.synchronizedMap(
        new LinkedHashMap<>() {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<String, V> eldest) {
            return size() > MAX_TRACKED_RUNS;
          }
        });
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
