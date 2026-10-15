// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UseConcurrentHashMap",
  "unchecked"
})
@NoArgsConstructor
class SessionMemoryPluginTest {

  private final SessionStore store = new SessionStore();
  private final VariableResolver resolver = mock(VariableResolver.class);
  private SessionMemoryPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new SessionMemoryPlugin(store, resolver);
    when(resolver.resolve(any(), any()))
        .thenAnswer(
            inv -> {
              final Object arg = inv.getArgument(0);
              return Mono.justOrEmpty(arg);
            });
  }

  private static Map<String, Object> load(final Object... extra) {
    final Map<String, Object> config = new HashMap<>();
    config.put("mode", "load");
    config.put("sessionId", "s1");
    config.put("message", "hi");
    for (int i = 0; i + 1 < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return config;
  }

  private Flux<Message<?>> run(final Object payload, final Map<String, Object> config) {
    return plugin.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), payload)), config);
  }

  @Test
  void metadataDescribesPlugin() {
    assertThat(plugin.getType()).isEqualTo("SESSION_MEMORY");
    assertThat(plugin.describe().description()).isNotBlank();
    assertThat(plugin.describe().usagePattern()).contains("load").contains("append");
  }

  @Test
  void loadAttachesHistoryRecordsUserTurnAndStampsSession() {
    store.append("s1", "user", "old q", 20);
    store.append("s1", "assistant", "old a", 20);

    StepVerifier.create(run(Map.of("message", "hi", "sessionId", "s1"), load()))
        .assertNext(
            m -> {
              final Map<String, Object> payload = (Map<String, Object>) m.payload();
              assertThat(payload).containsEntry("message", "hi");
              assertThat(payload.get("history"))
                  .isEqualTo(
                      List.of(
                          Map.of("role", "user", "content", "old q"),
                          Map.of("role", "assistant", "content", "old a")));
              assertThat(m.metadata()).containsEntry("sessionId", "s1");
              assertThat(m.sourcePort()).isEqualTo("default");
            })
        .verifyComplete();

    assertThat(store.history("s1", 10)).last().isEqualTo(Map.of("role", "user", "content", "hi"));
  }

  @Test
  void loadWrapsNonMapPayload() {
    StepVerifier.create(run("raw", load()))
        .assertNext(
            m -> {
              final Map<String, Object> payload = (Map<String, Object>) m.payload();
              assertThat(payload).containsEntry("input", "raw").containsKey("history");
            })
        .verifyComplete();
  }

  @Test
  void loadHonoursMaxTurnsWindow() {
    for (int i = 0; i < 5; i++) {
      store.append("s1", "user", "q" + i, 10);
    }

    StepVerifier.create(run(Map.of(), load("maxTurns", 2)))
        .assertNext(
            m ->
                assertThat(((Map<String, Object>) m.payload()).get("history"))
                    .isEqualTo(
                        List.of(
                            Map.of("role", "user", "content", "q3"),
                            Map.of("role", "user", "content", "q4"))))
        .verifyComplete();
    assertThat(store.history("s1", 10)).hasSize(2);
  }

  @Test
  void appendRecordsAssistantTurnAndPassesMessageThrough() {
    final Message<?> original = DefaultMessage.create(UUID.randomUUID(), Map.of("content", "x"));

    StepVerifier.create(
            plugin.process(
                Flux.just(original),
                Map.of("mode", "APPEND", "sessionId", "s9", "assistant", "the answer")))
        .assertNext(
            m -> {
              assertThat(m.payload()).isEqualTo(original.payload());
              assertThat(m.sourcePort()).isEqualTo("default");
            })
        .verifyComplete();

    assertThat(store.history("s9", 5))
        .containsExactly(Map.of("role", "assistant", "content", "the answer"));
  }

  @Test
  void appendDefaultsReadSessionFromMetadataAndReplyFromPayload() {
    when(resolver.resolve(eq("${metadata.sessionId}"), any())).thenReturn(Mono.just("s7"));
    when(resolver.resolve(eq("${payload.content}"), any())).thenReturn(Mono.just("reply"));

    StepVerifier.create(run(Map.of("content", "reply"), Map.of("mode", "append")))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(store.history("s7", 5))
        .containsExactly(Map.of("role", "assistant", "content", "reply"));
  }

  @Test
  void badModeFailsReactively() {
    StepVerifier.create(run(Map.of(), Map.of("mode", "nope")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(run(Map.of(), Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void validateConfigAcceptsValid() {
    StepVerifier.create(plugin.validateConfig(load("maxTurns", 5))).verifyComplete();
    StepVerifier.create(plugin.validateConfig(Map.of("mode", "append"))).verifyComplete();
  }

  @Test
  void validateConfigRejectsInvalid() {
    final Map<String, Object> noSession = load();
    noSession.remove("sessionId");
    final Map<String, Object> noMessage = load();
    noMessage.remove("message");
    final List<Map<String, Object>> invalid =
        List.of(Map.of(), Map.of("mode", "x"), noSession, noMessage, load("maxTurns", 0));
    for (final Map<String, Object> bad : invalid) {
      StepVerifier.create(plugin.validateConfig(bad))
          .expectError(IllegalArgumentException.class)
          .verify();
    }
  }
}
