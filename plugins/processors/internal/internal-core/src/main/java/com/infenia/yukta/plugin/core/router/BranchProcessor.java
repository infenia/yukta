// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.router;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginGuard;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.exception.NoMatchingBranchException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.SpelUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Branch plugin routes messages to different ports based on conditions or selectors. Supports
 * SELECT_KEY (exact match) and EXPRESSION (SpEL predicate) modes.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.CognitiveComplexity",
  "PMD.AvoidDeeplyNestedIfStmts",
  "PMD.ExceptionAsFlowControl",
  "PMD.AvoidCatchingGenericException",
  "PMD.UselessParentheses"
})
public final class BranchProcessor implements ProcessorPlugin {

  /** Metrics reporter. */
  private final PluginMetricsReporter reporter;

  /** Creates the plugin without metrics. */
  public BranchProcessor() {
    this.reporter = PluginMetricsReporter.NOOP;
  }

  /**
   * Spring constructor.
   *
   * @param reporterProvider optional metrics reporter
   */
  @Autowired
  public BranchProcessor(final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    this.reporter = reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP);
  }

  /** Type. */
  private static final String TYPE = "BRANCH";

  /** Config mode. */
  private static final String CONFIG_MODE = "mode";

  /** Config selector. */
  private static final String CONFIG_SELECTOR = "selector";

  /** Config cases. */
  private static final String CONFIG_CASES = "cases";

  /** Def port. */
  private static final String DEF_PORT = "defaultPort";

  /** Allow mult. */
  private static final String ALLOW_MULT = "allowMultipleMatches";

  /** Strict. */
  private static final String STRICT = "strictMode";

  /** Error port. */
  private static final String ERROR_PORT = "errorPort";

  /** Mode select key. */
  private static final String MODE_SELECT_KEY = "SELECT_KEY";

  /** Mode expression. */
  private static final String MODE_EXPRESSION = "EXPRESSION";

  /** Err prefix. */
  private static final String ERR_PREFIX = "WorkflowExecutionException: ";

  /** Unchecked. */
  private static final String UNCHECKED = "unchecked";

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Routes messages to different ports based on conditions or selectors. Supports exact"
            + " match and SpEL predicate modes.",
        "Configure with:\n"
            + "- mode: 'SELECT_KEY' (evaluates selector and looks up in cases) or 'EXPRESSION'"
            + " (evaluates each case key as a predicate).\n"
            + "- selector: SpEL expression used in 'SELECT_KEY' mode.\n"
            + "- cases: Map where keys are match values (SELECT_KEY) or SpEL predicates"
            + " (EXPRESSION), and values are output port names.\n"
            + "- defaultPort: Optional port name if no matches are found.\n"
            + "- allowMultipleMatches: Boolean. If true, message can be routed to multiple ports."
            + " Default is false.\n"
            + "- strictMode: Boolean. If true (default), throws exception if no branch matches and"
            + " no defaultPort is set.\n"
            + "- errorPort: Optional port name to route messages when evaluation fails. Adds"
            + " failure metadata to the message.",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-2 border-slate-200 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-indigo-100 rounded-lg flex items-center justify-center text-indigo-500">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7h8a2 2 0 012 2v10M8 17H6a2 2 0 01-2-2V5a2 2 0 012-2h8a2 2 0 012 2v2M21 21l-3-3m0 0l-3 3m3-3v-8"/>
                        </svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">Branch</div>
                        <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                    </div>
                </div>
                """,
                140,
                80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.choice(
            "mode", true, "SELECT_KEY", "How the branch is chosen", "SELECT_KEY", "EXPRESSION"),
        ConfigField.required("selector", ConfigField.Type.EXPRESSION, "Key or expression to match"),
        ConfigField.required("cases", ConfigField.Type.MAP, "Match value (or predicate) to port"),
        ConfigField.optional(
            "defaultPort", ConfigField.Type.STRING, null, "Port when nothing matches"),
        ConfigField.optional(
            "allowMultipleMatches", ConfigField.Type.BOOLEAN, false, "Emit on every matching case"),
        ConfigField.optional("strictMode", ConfigField.Type.BOOLEAN, true, "Fail on eval errors"),
        ConfigField.optional("errorPort", ConfigField.Type.STRING, null, "Port for errors"));
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final Map<String, String> cases =
        (Map<String, String>) config.getOrDefault(CONFIG_CASES, Map.of());
    final String defaultPort = (String) config.get(DEF_PORT);
    final String errorPort = (String) config.get(ERROR_PORT);

    final Set<String> ports = new HashSet<>(cases.values());
    if (defaultPort != null && !defaultPort.isBlank()) {
      ports.add(defaultPort);
    }
    if (errorPort != null && !errorPort.isBlank()) {
      ports.add(errorPort);
    }

    if (ports.isEmpty()) {
      return List.of("default");
    }
    return new ArrayList<>(ports);
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Mono<Void> prepare(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    if (MODE_SELECT_KEY.equals(mode)) {
      SpelUtils.preParse((String) config.get(CONFIG_SELECTOR));
    } else if (MODE_EXPRESSION.equals(mode)) {
      final Map<String, String> cases = (Map<String, String>) config.get(CONFIG_CASES);
      if (cases != null) {
        cases.keySet().forEach(SpelUtils::preParse);
      }
    }
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return PluginGuard.counted(doProcess(input, config), reporter, "branch");
  }

  @SuppressWarnings(UNCHECKED)
  private Flux<Message<?>> doProcess(
      final Flux<Message<?>> input, final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    final String selector = (String) config.get(CONFIG_SELECTOR);
    final Map<String, String> cases =
        (Map<String, String>) config.getOrDefault(CONFIG_CASES, Map.of());
    final String defaultPort = (String) config.get(DEF_PORT);
    final boolean allowMultiple = (Boolean) config.getOrDefault(ALLOW_MULT, false);
    final boolean strictMode = (Boolean) config.getOrDefault(STRICT, true);
    final String errorPort = (String) config.get(ERROR_PORT);

    return input.flatMap(
        message ->
            Flux.defer(
                () -> {
                  final List<String> matchedPorts = new ArrayList<>();

                  try {
                    evaluateBranches(mode, selector, cases, allowMultiple, message, matchedPorts);

                    if (matchedPorts.isEmpty()) {
                      if (defaultPort != null) {
                        matchedPorts.add(defaultPort);
                      } else if (strictMode) {
                        throw new NoMatchingBranchException(
                            ERR_PREFIX
                                + "No matching branch found for message "
                                + message.id()
                                + " and no default port configured");
                      }
                    }

                    return Flux.fromIterable(matchedPorts).map(message::withSourcePort);

                  } catch (final Exception exception) {
                    return handleException(message, errorPort, strictMode, exception);
                  }
                }));
  }

  private Flux<Message<?>> handleException(
      final Message<?> message,
      final String errorPort,
      final boolean strictMode,
      final Exception exception) {
    log.error("Branch evaluation failed for message {}: {}", message.id(), exception.getMessage());
    if (errorPort != null) {
      return Flux.just(
          message
              .withSourcePort(errorPort)
              .withFailure(null, "Branch evaluation failed", exception.getMessage()));
    }
    if (!strictMode) {
      return Flux.empty();
    }
    return Flux.error(
        exception instanceof NoMatchingBranchException
            ? exception
            : new RuntimeException(ERR_PREFIX + "Branch evaluation failed", exception));
  }

  private void evaluateBranches(
      final String mode,
      final String selector,
      final Map<String, String> cases,
      final boolean allowMultiple,
      final Message<?> message,
      final List<String> matchedPorts) {
    if (MODE_SELECT_KEY.equals(mode)) {
      final Object result = SpelUtils.evaluateSync(selector, message);
      if (result != null) {
        final String port = cases.get(result.toString());
        if (port != null) {
          matchedPorts.add(port);
        }
      }
    } else if (MODE_EXPRESSION.equals(mode)) {
      for (final Map.Entry<String, String> entry : cases.entrySet()) {
        final Boolean match = SpelUtils.evaluateSync(entry.getKey(), message);
        if (Boolean.TRUE.equals(match)) {
          matchedPorts.add(entry.getValue());
          if (!allowMultiple) {
            break;
          }
        }
      }
    }
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    if ((!MODE_SELECT_KEY.equals(mode) && !MODE_EXPRESSION.equals(mode))) {
      return Mono.error(
          new IllegalArgumentException(
              "Invalid or missing mode. Must be SELECT_KEY or EXPRESSION"));
    }
    if (MODE_SELECT_KEY.equals(mode) && (config.get(CONFIG_SELECTOR) == null)) {
      return Mono.error(new IllegalArgumentException("selector is mandatory for SELECT_KEY mode"));
    }
    final Map<String, String> cases = (Map<String, String>) config.get(CONFIG_CASES);
    if (cases == null || cases.isEmpty()) {
      return Mono.error(new IllegalArgumentException("cases map is mandatory and cannot be empty"));
    }
    return Mono.empty();
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Mono<Void> validateInContext(
      final WorkflowContext context, final Map<String, Object> config) {
    // Each case/default port must have an outgoing edge
    final Map<String, String> cases =
        (Map<String, String>) config.getOrDefault(CONFIG_CASES, Map.of());
    final String defaultPort = (String) config.get(DEF_PORT);

    final Set<String> definedPorts = new HashSet<>();
    if (cases != null) {
      definedPorts.addAll(cases.values());
    }
    if (defaultPort != null) {
      definedPorts.add(defaultPort);
    }

    final Set<String> actualPorts =
        context.outgoingEdges().stream()
            .map(WorkflowContext.WorkflowEdge::sourcePort)
            .collect(Collectors.toSet());

    return Flux.fromIterable(definedPorts)
        .flatMap(
            definedPort -> {
              if (!actualPorts.contains(definedPort)) {
                return Mono.error(
                    new IllegalArgumentException(
                        "Branch node "
                            + context.nodeId()
                            + " has a case routing to port '"
                            + definedPort
                            + "' but no outgoing edge exists for this port"));
              }
              return Mono.empty();
            })
        .then();
  }
}
