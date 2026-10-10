// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Service;

/**
 * Gateway calling LLM providers through Spring AI.
 *
 * <p>A {@link ChatModel} owns an HTTP client, so one is built per distinct connection/sampling
 * configuration and reused. Calls are blocking; callers must schedule them accordingly.
 */
@SuppressWarnings("PMD.LawOfDemeter")
@Service
@NoArgsConstructor
public class LlmGateway {

  /** Models by their connection and sampling configuration. */
  // ponytail: unbounded map; node configs are static so it stays small, evict if configs become
  // dynamic
  private final Map<LlmRequest, ChatModel> models = new ConcurrentHashMap<>();

  /**
   * Call the model and return its answer.
   *
   * @param request the resolved request
   * @return the model answer
   */
  public LlmResponse call(final LlmRequest request) {
    final ChatResponse response =
        models.computeIfAbsent(cacheKey(request), this::build).call(new Prompt(messages(request)));
    final var result = response.getResult();
    final String text = result == null ? null : result.getOutput().getText();
    final var metadata = response.getMetadata();
    return new LlmResponse(
        Objects.toString(text, ""),
        metadata.getUsage().getPromptTokens(),
        metadata.getUsage().getCompletionTokens(),
        Objects.toString(metadata.getModel(), request.model()));
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
        List.of());
  }

  private static List<Message> messages(final LlmRequest request) {
    final List<Message> messages = new ArrayList<>();
    if (request.system() != null && !request.system().isBlank()) {
      messages.add(new SystemMessage(request.system()));
    }
    for (final ChatTurn turn : request.messages()) {
      messages.add(
          "assistant".equals(turn.role().toLowerCase(Locale.ROOT))
              ? new AssistantMessage(turn.content())
              : new UserMessage(turn.content()));
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
