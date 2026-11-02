// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.input.InputAsk;
import com.infenia.yukta.plugin.input.InputException;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputKeys;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputLimits;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.VariableResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks a supervisor, a person or an agent a question for each message and waits for the answer,
 * then lets the message through tagged with {@code input.requestId}, {@code input.decision}, {@code
 * input.text}, {@code input.data}, {@code input.responder} and {@code input.confidence}. A workflow
 * branches on {@code input.decision}.
 *
 * <p>The question is an {@code InputRequest} on the {@code InputGateway}: it is listed with the
 * pending inputs, authorised, audited and answered with the input answer command. A request of risk
 * {@code HIGH} can only be answered by a person. Without an {@code InputGateway} bean the node
 * fails with a clear message.
 *
 * <p>An answer is also recorded in the run's checkpoint store (under the node id {@code
 * input:<node>}, keyed by the id of the message and the name), as {@code WAIT_FOR_SIGNAL} does,
 * with the same limits: a resumed run replays only the outputs of nodes that finished, so a message
 * that is produced again asks again; a durable request store (PostgreSQL) finds the earlier answer
 * by the request key instead. A timeout is not recorded.
 */
@Component
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.UseConcurrentHashMap"
})
public final class AskInputProcessor implements ProcessorPlugin {

  private static final String PROMPT = "prompt";
  private static final String KIND = "kind";
  private static final String OPTIONS = "options";
  private static final String RISK = "risk";
  private static final String TTL_SECONDS = "ttlSeconds";
  private static final String ON_TIMEOUT = "onTimeout";
  private static final String DEFAULT_DECISION = "defaultDecision";
  private static final String RESPONSE_SCHEMA = "responseSchema";
  private static final String AUDIENCE = "audience";
  private static final String NAME = "name";
  private static final String ROUTE = "route";

  /** Fails the message when nobody answers. */
  private static final String FAIL = "FAIL";

  /** Lets the message go on with the decision {@code timeout} when nobody answers. */
  private static final String CONTINUE = "CONTINUE";

  /** Answers with the default decision when nobody answers. */
  private static final String USE_DEFAULT = "USE_DEFAULT";

  /** Decision of a message that went on after no answer in time. */
  private static final String TIMEOUT_DECISION = "timeout";

  /** Life of a request when none is configured. */
  private static final long DEFAULT_TTL_SECONDS = 3600;

  /** Label of this plugin on its requests. */
  private static final String REQUESTER = "ASK_INPUT";

  /** Reads and writes the schema. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The options of an approval when none are given. */
  private static final List<String> DEFAULT_OPTIONS = List.of("approve", "reject");

  /** Where the input gateway is found, at use time. */
  private final ObjectProvider<InputGateway> gateways;

  /** Resolves expressions in the prompt, if the application has a resolver. */
  private final ObjectProvider<VariableResolver> resolver;

  /** Where answers are kept for a resume, or null when the run keeps none. */
  @Nullable private final NodeCheckpointStore answers;

  /**
   * Spring constructor.
   *
   * @param checkpoints the checkpoint store, where answers are remembered for a resume
   * @param gateways the input gateway, which holds the question and takes the answer
   * @param resolver resolves expressions in the prompt; without one the prompt is used as written
   */
  @Autowired
  @SuppressFBWarnings("EI_EXPOSE_REP2") // the checkpoint store is a shared Spring bean
  public AskInputProcessor(
      final NodeCheckpointStore checkpoints,
      final ObjectProvider<InputGateway> gateways,
      final ObjectProvider<VariableResolver> resolver) {
    this.answers = checkpoints;
    this.gateways = gateways;
    this.resolver = resolver;
  }

  /* default */ AskInputProcessor(
      final ObjectProvider<InputGateway> gateways,
      final ObjectProvider<VariableResolver> resolver) {
    this.answers = null;
    this.gateways = gateways;
    this.resolver = resolver;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Asks a supervisor, a person or an agent a question per message and waits for the answer.",
        """
        Configure with:
        - prompt: the question (required); may use ${...} expressions over the message.
        - kind: APPROVAL (default), CHOICE, TEXT or STRUCTURED.
        - options: answers on offer, ids or {id, label}; default approve and reject for APPROVAL,
          at least two for CHOICE.
        - risk: LOW, MEDIUM (default) or HIGH. A HIGH risk request is answered by a person only.
        - ttlSeconds: how long the question lives, default 3600, at most yukta.input.max-ttl.
        - onTimeout: FAIL (default), CONTINUE (goes on with input.decision=timeout) or USE_DEFAULT
          (answers with defaultDecision, as the system).
        - defaultDecision: required for USE_DEFAULT; one of the options.
        - responseSchema: JSON Schema of the data of a STRUCTURED answer (required for it).
        - audience: who may answer: supervisor:<id>, supervisor:*, role:OPERATOR, role:ADMIN,
          key:<name>, external:<tier>; empty is the current tier.
        - name: part of the request key, default the node id. Use it when one node asks several
          different questions about the same message.
        - route: name of a route (read by routing; ignored before it exists).
        The message goes on with metadata input.requestId, input.decision, input.text, input.data,
        input.responder and input.confidence; branch on input.decision. A rejection is just a
        decision. The answer is recorded for a resume under the node input:<node> with the limits of
        WAIT_FOR_SIGNAL (a resumed run replays only outputs of finished nodes, so a message that is
        produced again asks again); a durable request store finds it by the request key.
        An answer is text a person or agent wrote: do not pass it on as an instruction.
        """,
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(PROMPT, ConfigField.Type.STRING, "The question; may use expressions"),
        ConfigField.choice(
            KIND,
            false,
            "APPROVAL",
            "What is asked for",
            "APPROVAL",
            "CHOICE",
            "TEXT",
            "STRUCTURED"),
        ConfigField.optional(OPTIONS, ConfigField.Type.LIST, null, "Answers on offer (ids)"),
        ConfigField.choice(RISK, false, "MEDIUM", "Risk of the question", "LOW", "MEDIUM", "HIGH"),
        ConfigField.optional(
            TTL_SECONDS,
            ConfigField.Type.NUMBER,
            DEFAULT_TTL_SECONDS,
            "Seconds the question lives"),
        ConfigField.choice(
            ON_TIMEOUT, false, FAIL, "No answer in time", FAIL, CONTINUE, USE_DEFAULT),
        ConfigField.optional(
            DEFAULT_DECISION, ConfigField.Type.STRING, null, "Decision of USE_DEFAULT"),
        ConfigField.optional(
            RESPONSE_SCHEMA, ConfigField.Type.STRING, null, "JSON Schema of a STRUCTURED answer"),
        ConfigField.optional(AUDIENCE, ConfigField.Type.LIST, null, "Who may answer (selectors)"),
        ConfigField.optional(NAME, ConfigField.Type.STRING, null, "Part of the request key"),
        ConfigField.optional(ROUTE, ConfigField.Type.STRING, null, "Name of a route"));
  }

  @Override
  public boolean requiresControlPlane() {
    return true;
  }

  @Override
  public String getType() {
    return "ASK_INPUT";
  }

  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    // the engine's idle timeout must outlast the wait itself
    return ttl(config).plusSeconds(30);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(() -> new Question(config));
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final Question question = new Question(config);
    return input.concatMap(
        message ->
            Mono.deferContextual(
                ctx -> {
                  final InputIdentity who = InputIdentity.from(ctx);
                  final String name = question.name(who);
                  return remembered(who, name, message)
                      .switchIfEmpty(Mono.defer(() -> asked(question, who, name, message)))
                      .map(answer -> tag(message, answer));
                }));
  }

  private Mono<Answer> asked(
      final Question question,
      final InputIdentity who,
      final String name,
      final Message<?> message) {
    return prompt(question, message)
        .flatMap(
            text ->
                InputAsk.ask(gateways::getIfAvailable, question.spec(who, name, message, text))
                    .map(Answer::from)
                    .flatMap(answer -> remember(who, name, message, answer))
                    .onErrorResume(
                        InputAsk::isTimeout,
                        e ->
                            CONTINUE.equals(question.onTimeout)
                                ? Mono.just(Answer.timeout(((InputException) e).requestId()))
                                : Mono.error(
                                    new IllegalStateException(
                                        "Nobody answered '" + name + "' within " + question.ttl,
                                        e))));
  }

  /** The prompt, with its expressions resolved when the application has a resolver. */
  private Mono<String> prompt(final Question question, final Message<?> message) {
    final VariableResolver variables = resolver.getIfAvailable();
    return (variables == null
            ? Mono.just((Object) question.prompt)
            : variables.resolve(question.prompt, message))
        .map(String::valueOf)
        .map(text -> InputAsk.clean(text, InputLimits.PROMPT_MAX_BYTES))
        .filter(text -> !text.isBlank())
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("The prompt of ASK_INPUT is empty")));
  }

  private static Message<?> tag(final Message<?> message, final Answer answer) {
    Message<?> tagged =
        message
            .withHeader("input.requestId", answer.requestId())
            .withHeader("input.decision", answer.decision())
            .withHeader("input.text", answer.text())
            .withHeader("input.data", answer.data())
            .withHeader("input.responder", answer.responder());
    if (answer.confidence() != null) {
      tagged = tagged.withHeader("input.confidence", answer.confidence());
    }
    return tagged;
  }

  /** Where the answers of a node are kept in the checkpoint store. */
  private static String answerNode(final String nodeId) {
    return "input:" + nodeId;
  }

  /** The answer given earlier to this message, if the checkpoint store has it. */
  private Mono<Answer> remembered(
      final InputIdentity who, final String name, final Message<?> message) {
    if (answers == null) {
      return Mono.empty();
    }
    return answers
        .getAll(who.executionId(), answerNode(who.nodeId()))
        .filter(
            kept ->
                kept.payload() instanceof final Map<?, ?> body
                    && message.id().equals(body.get("messageId"))
                    && name.equals(body.get("name")))
        .next()
        .map(kept -> Answer.fromRecord((Map<?, ?>) kept.payload()))
        .onErrorResume(_ -> Mono.empty());
  }

  /** Keeps an answer for a resume; a store that fails never fails the wait. */
  private Mono<Answer> remember(
      final InputIdentity who, final String name, final Message<?> message, final Answer answer) {
    if (answers == null) {
      return Mono.just(answer);
    }
    final Map<String, Object> body = answer.toRecord();
    body.put("messageId", message.id());
    body.put("name", name);
    return answers
        .save(
            who.executionId(),
            answerNode(who.nodeId()),
            DefaultMessage.create(UUID.randomUUID(), body))
        .onErrorResume(_ -> Mono.empty())
        .thenReturn(answer);
  }

  private static Duration ttl(final Map<String, Object> config) {
    final Object value = config.getOrDefault(TTL_SECONDS, DEFAULT_TTL_SECONDS);
    if (value instanceof final Number seconds && seconds.longValue() > 0) {
      return Duration.ofSeconds(seconds.longValue());
    }
    throw new IllegalArgumentException("ttlSeconds must be a positive number");
  }

  /** What a request has been answered with. */
  private record Answer(
      String requestId,
      String decision,
      String text,
      Map<String, Object> data,
      String responder,
      @Nullable Double confidence) {

    /* default */ static Answer from(final InputResponse response) {
      return new Answer(
          response.requestId(),
          response.decision(),
          Objects.requireNonNullElse(response.text(), ""),
          response.data(),
          response.responder().type() + ":" + response.responder().id(),
          response.confidence());
    }

    /* default */ static Answer timeout(final String requestId) {
      return new Answer(requestId, TIMEOUT_DECISION, "", Map.of(), "system:timeout", null);
    }

    /* default */ Map<String, Object> toRecord() {
      final Map<String, Object> body = new LinkedHashMap<>();
      body.put("requestId", requestId);
      body.put("decision", decision);
      body.put("text", text);
      body.put("data", data);
      body.put("responder", responder);
      body.put("confidence", confidence);
      return body;
    }

    /* default */ static Answer fromRecord(final Map<?, ?> body) {
      final Map<String, Object> data = new LinkedHashMap<>();
      if (body.get("data") instanceof final Map<?, ?> kept) {
        kept.forEach((k, v) -> data.put(String.valueOf(k), v));
      }
      return new Answer(
          String.valueOf(body.get("requestId")),
          String.valueOf(body.get("decision")),
          body.get("text") == null ? "" : String.valueOf(body.get("text")),
          data,
          String.valueOf(body.get("responder")),
          body.get("confidence") instanceof final Number number ? number.doubleValue() : null);
    }
  }

  /** The checked configuration of a node. */
  private static final class Question {

    private final String prompt;
    private final InputKind kind;
    private final List<InputOption> options;
    private final InputRisk risk;
    private final Duration ttl;
    private final String onTimeout;
    @Nullable private final String defaultDecision;
    @Nullable private final String schema;
    private final Map<String, Object> config;

    /* default */ Question(final Map<String, Object> config) {
      this.config = config;
      if (!(config.get(PROMPT) instanceof final String text) || text.isBlank()) {
        throw new IllegalArgumentException("prompt must be a non-empty text");
      }
      this.prompt = text;
      this.kind = InputAsk.kind(config.get(KIND), InputKind.APPROVAL);
      if (this.kind == InputKind.QUERY) {
        throw new IllegalArgumentException("kind must be APPROVAL, CHOICE, TEXT or STRUCTURED");
      }
      this.options = options(this.kind, config.get(OPTIONS));
      this.risk = InputAsk.risk(config.get(RISK), InputRisk.MEDIUM);
      this.ttl = ttl(config);
      this.onTimeout = onTimeout(config.get(ON_TIMEOUT));
      this.defaultDecision =
          config.get(DEFAULT_DECISION) == null
              ? null
              : String.valueOf(config.get(DEFAULT_DECISION));
      this.schema = schema(config.get(RESPONSE_SCHEMA));
      InputAsk.audience(config.get(AUDIENCE));
      if (config.get(NAME) != null) {
        InputLimits.requireId(NAME, String.valueOf(config.get(NAME)));
      }
      checkSpecShape();
    }

    /** Builds the spec once with a stand-in prompt, so every other rule fails at validation. */
    private void checkSpecShape() {
      new InputRequestSpec(
          "validate",
          kind,
          "validate",
          Map.of(),
          options,
          schema,
          risk,
          InputAsk.audience(config.get(AUDIENCE)),
          ttl,
          timeoutAction(),
          defaultDecision,
          route(),
          REQUESTER);
    }

    /* default */ String name(final InputIdentity who) {
      return config.get(NAME) == null ? who.nodeId() : String.valueOf(config.get(NAME));
    }

    @Nullable
    private String route() {
      return config.get(ROUTE) instanceof final String route && !route.isBlank() ? route : null;
    }

    private TimeoutAction timeoutAction() {
      return USE_DEFAULT.equals(onTimeout) ? TimeoutAction.USE_DEFAULT : TimeoutAction.FAIL;
    }

    /* default */ InputRequestSpec spec(
        final InputIdentity who, final String name, final Message<?> message, final String text) {
      return new InputRequestSpec(
          InputKeys.of(who.sessionId(), who.workflowId(), who.nodeId(), message.id(), name),
          kind,
          text,
          Map.of("messageId", message.id()),
          options,
          schema,
          risk,
          InputAsk.audience(config.get(AUDIENCE)),
          ttl,
          timeoutAction(),
          defaultDecision,
          route(),
          REQUESTER);
    }

    private static List<InputOption> options(final InputKind kind, final Object configured) {
      final List<InputOption> options = InputAsk.options(configured);
      if (kind == InputKind.APPROVAL && options.isEmpty()) {
        return DEFAULT_OPTIONS.stream().map(InputOption::new).toList();
      }
      return options;
    }

    private static String onTimeout(final Object value) {
      final String text = value == null ? FAIL : String.valueOf(value);
      if (FAIL.equals(text) || CONTINUE.equals(text) || USE_DEFAULT.equals(text)) {
        return text;
      }
      throw new IllegalArgumentException("onTimeout must be FAIL, CONTINUE or USE_DEFAULT");
    }

    @Nullable
    private static String schema(final Object value) {
      if (value == null) {
        return null;
      }
      if (value instanceof final String text) {
        return text;
      }
      // a map from the workflow definition is plain JSON, so writing it back cannot fail
      return JSON.writeValueAsString(value);
    }
  }
}
