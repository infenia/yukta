// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Gateway calling LLM providers through Spring AI.
 *
 * <p>A {@link ChatModel} owns an HTTP client, so one is built per distinct connection/sampling
 * configuration and reused. Calls are blocking; callers must schedule them accordingly.
 */
@SuppressWarnings({
  "PMD.LawOfDemeter",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects"
})
@Service
@NoArgsConstructor
public class LlmGateway {

  /** Writes tool input schemas. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** The prompt must not match any of these. */
  private List<Pattern> denyInput = List.of();

  /** The answer must not match any of these. */
  private List<Pattern> denyOutput = List.of();

  /** The most characters of prompt, or 0 for no limit. */
  private int maxInputChars;

  /** Most models kept; the least recently used are dropped beyond this many. */
  private static final long MODEL_CACHE_MAX = 100;

  /**
   * Models by their connection and sampling configuration. A dropped model is built again when it
   * is next used.
   */
  private final Cache<LlmRequest, ChatModel> models =
      Caffeine.newBuilder().maximumSize(MODEL_CACHE_MAX).executor(Runnable::run).build();

  /** Spaces the calls to each provider, when {@code yukta.llm.rate-limit.<provider>} is set. */
  private final ProviderRateLimits rateLimits = new ProviderRateLimits();

  /**
   * Call the model and return its answer.
   *
   * @param request the resolved request
   * @return the model answer
   */
  public LlmResponse call(final LlmRequest request) {
    refuseIfDenied("prompt", "yukta.llm.deny-input-patterns", inputParts(request), denyInput);
    waitForTurn(request);
    final LlmResponse answer = invoke(request);
    refuseIfDenied("answer", "yukta.llm.deny-output-patterns", outputParts(answer), denyOutput);
    return answer;
  }

  /**
   * Reads both pattern lists. A plain value is split at commas, so write a pattern that has one
   * (such as {@code a{1,3}}) as an indexed entry: {@code yukta.llm.deny-input-patterns[0]}.
   *
   * @param environment the environment
   */
  @Autowired
  /* default */ void readPatterns(final Environment environment) {
    final Binder binder = Binder.get(environment);
    setDenyInput(patterns(binder, "yukta.llm.deny-input-patterns"));
    setDenyOutput(patterns(binder, "yukta.llm.deny-output-patterns"));
    rateLimits.configure(limits(binder));
  }

  private static Map<String, Integer> limits(final Binder binder) {
    return binder
        .bind("yukta.llm.rate-limit", Bindable.mapOf(String.class, Integer.class))
        .orElse(Map.of());
  }

  /** Sleeps until the provider's rate limit gives this call its turn (the caller is blocking). */
  private void waitForTurn(final LlmRequest request) {
    final long wait =
        rateLimits.reserve(
            request.provider().name(), System.nanoTime(), request.timeout().dividedBy(2));
    if (wait > 0) {
      try {
        TimeUnit.NANOSECONDS.sleep(wait);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the rate limit", e);
      }
    }
  }

  /* default */ void setRateLimits(final Map<String, Integer> perMinute) {
    rateLimits.configure(perMinute);
  }

  private static List<String> patterns(final Binder binder, final String name) {
    return binder.bind(name, Bindable.listOf(String.class)).orElse(List.of());
  }

  /**
   * Sets regular expressions the prompt must not match ({@code yukta.llm.deny-input-patterns}),
   * e.g. for secrets or known injection phrases.
   *
   * @param patterns the expressions
   */
  /* default */ void setDenyInput(final List<String> patterns) {
    this.denyInput = patterns.stream().map(Pattern::compile).toList();
  }

  /**
   * Sets regular expressions the answer and its tool-call arguments must not match ({@code
   * yukta.llm.deny-output-patterns}).
   *
   * @param patterns the expressions
   */
  /* default */ void setDenyOutput(final List<String> patterns) {
    this.denyOutput = patterns.stream().map(Pattern::compile).toList();
  }

  /**
   * Sets the most characters of prompt one call may carry ({@code yukta.llm.max-input-chars}); 0 is
   * no limit.
   *
   * @param maxChars the limit
   */
  @Value("${yukta.llm.max-input-chars:0}")
  /* default */ void setMaxInputChars(final int maxChars) {
    this.maxInputChars = maxChars;
  }

  /** Each field on its own, so an anchored pattern cannot be satisfied by a neighbouring field. */
  private static List<String> inputParts(final LlmRequest request) {
    return Stream.concat(
            Stream.of(Objects.toString(request.system(), "")),
            request.messages().stream().map(ChatTurn::content))
        .toList();
  }

  private static List<String> outputParts(final LlmResponse answer) {
    return Stream.concat(
            Stream.of(answer.text()),
            answer.toolCalls().stream().map(call -> Objects.toString(call.arguments(), "")))
        .toList();
  }

  private void refuseIfDenied(
      final String what,
      final String property,
      final List<String> parts,
      final List<Pattern> denied) {
    if (maxInputChars > 0
        && "prompt".equals(what)
        && parts.stream().mapToInt(String::length).sum() > maxInputChars) {
      throw new IllegalArgumentException("The prompt exceeds yukta.llm.max-input-chars");
    }
    if (parts.stream()
        .anyMatch(part -> denied.stream().anyMatch(pattern -> pattern.matcher(part).find()))) {
      throw new IllegalArgumentException("The " + what + " matches " + property);
    }
  }

  /**
   * Number of models kept.
   *
   * @return the cache size
   */
  /* default */ long modelCacheSize() {
    models.cleanUp();
    return models.estimatedSize();
  }

  private LlmResponse invoke(final LlmRequest request) {
    final ChatResponse response = models.get(cacheKey(request), this::build).call(prompt(request));
    final var result = response.getResult();
    final String text = result == null ? null : result.getOutput().getText();
    final List<ToolCallRequest> toolCalls =
        result == null
            ? List.of()
            : result.getOutput().getToolCalls().stream()
                .map(call -> new ToolCallRequest(call.id(), call.name(), call.arguments()))
                .toList();
    final var metadata = response.getMetadata();
    return new LlmResponse(
        Objects.toString(text, ""),
        metadata.getUsage().getPromptTokens(),
        metadata.getUsage().getCompletionTokens(),
        Objects.toString(metadata.getModel(), request.model()),
        toolCalls);
  }

  /**
   * The prompt. With tools, the model gets their definitions and may answer with calls to them; the
   * engine does not run them here, the workflow does (for example an MCP_TOOL_CALL node).
   */
  private static Prompt prompt(final LlmRequest request) {
    final List<Message> messages = messages(request);
    final List<ToolCallback> callbacks =
        request.tools().stream().<ToolCallback>map(DefinitionOnlyTool::new).toList();
    return callbacks.isEmpty()
        ? new Prompt(messages)
        : new Prompt(messages, toolOptions(request.provider(), callbacks));
  }

  /** Options that only add the tools; a provider accepts only its own kind of options. */
  private static ChatOptions toolOptions(
      final Provider provider, final List<ToolCallback> callbacks) {
    return switch (provider) {
      case OPENAI -> OpenAiChatOptions.builder().toolCallbacks(callbacks).build();
      case ANTHROPIC -> AnthropicChatOptions.builder().toolCallbacks(callbacks).build();
      case OLLAMA -> OllamaChatOptions.builder().toolCallbacks(callbacks).build();
    };
  }

  /** A tool the model is told about but that this gateway never runs. */
  /* default */ record DefinitionOnlyTool(ToolSpec spec) implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name(spec.name())
          .description(spec.description())
          .inputSchema(JSON.writeValueAsString(spec.inputSchema()))
          .build();
    }

    @Override
    public String call(final String toolInput) {
      throw new UnsupportedOperationException(
          "Tool " + spec.name() + " is run by the workflow, not by the LLM node");
    }
  }

  /** Strip what does not affect the model, so equal configurations share one model. */
  private static LlmRequest cacheKey(final LlmRequest request) {
    return new LlmRequest(
        request.provider(),
        request.baseUrl(),
        request.apiKey(),
        request.model(),
        request.temperature(),
        request.maxTokens(),
        request.timeout(),
        null,
        List.of(),
        List.of());
  }

  private static Message message(final ChatTurn turn) {
    return switch (turn.role().toLowerCase(Locale.ROOT)) {
      case "assistant" ->
          turn.toolCalls().isEmpty()
              ? new AssistantMessage(turn.content())
              : AssistantMessage.builder()
                  .content(turn.content())
                  .toolCalls(
                      turn.toolCalls().stream()
                          .map(
                              call ->
                                  new AssistantMessage.ToolCall(
                                      call.id(), "function", call.name(), call.arguments()))
                          .toList())
                  .build();
      case "tool" ->
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(
                          Objects.toString(turn.toolCallId(), ""),
                          Objects.toString(turn.toolName(), ""),
                          turn.content())))
              .build();
      default -> new UserMessage(turn.content());
    };
  }

  private static List<Message> messages(final LlmRequest request) {
    final List<Message> messages = new ArrayList<>();
    if (request.system() != null && !request.system().isBlank()) {
      messages.add(new SystemMessage(request.system()));
    }
    for (final ChatTurn turn : request.messages()) {
      messages.add(message(turn));
    }
    return messages;
  }

  private ChatModel build(final LlmRequest key) {
    return switch (key.provider()) {
      case OPENAI -> {
        final var options =
            OpenAiChatOptions.builder()
                .baseUrl(key.baseUrl())
                .apiKey(key.apiKey())
                .model(key.model())
                .temperature(key.temperature())
                .maxTokens(key.maxTokens())
                .timeout(key.timeout())
                .maxRetries(0)
                .build();
        yield OpenAiChatModel.builder().options(options).build();
      }
      case ANTHROPIC -> {
        final var options =
            AnthropicChatOptions.builder()
                .baseUrl(key.baseUrl())
                .apiKey(key.apiKey())
                .model(key.model())
                .temperature(key.temperature())
                .maxTokens(key.maxTokens())
                .timeout(key.timeout())
                .maxRetries(0)
                .build();
        yield AnthropicChatModel.builder().options(options).build();
      }
      case OLLAMA -> {
        final var api = OllamaApi.builder();
        if (key.baseUrl() != null) {
          api.baseUrl(key.baseUrl());
        }
        final var options =
            OllamaChatOptions.builder()
                .model(key.model())
                .temperature(key.temperature())
                .numPredict(key.maxTokens())
                .build();
        // no built-in retries: the plugin owns retry policy, as with maxRetries(0) above
        yield OllamaChatModel.builder()
            .ollamaApi(api.build())
            .options(options)
            .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
            .build();
      }
    };
  }
}
