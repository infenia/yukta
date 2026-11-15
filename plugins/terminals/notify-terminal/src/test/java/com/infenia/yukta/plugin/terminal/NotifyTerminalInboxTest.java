// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.notify.NotificationGateway;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.plugin.notify.NotificationSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** {@code target: inbox} raises a notification through the gateway instead of calling a URL. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class NotifyTerminalInboxTest {

  private final List<NotificationSpec> raised = new ArrayList<>();
  private boolean fail;

  private NotifyTerminal terminal(final boolean withGateway) {
    final NotifyTerminal terminal = new NotifyTerminal(WebClient.builder());
    if (withGateway) {
      final NotificationGateway gateway =
          spec -> {
            raised.add(spec);
            return fail ? Mono.error(new IllegalStateException("limit")) : Mono.empty();
          };
      terminal.setNotificationGateway(provider(gateway));
    }
    return terminal;
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<NotificationGateway> provider(final NotificationGateway gateway) {
    final ObjectProvider<NotificationGateway> provider =
        org.mockito.Mockito.mock(ObjectProvider.class);
    org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(gateway);
    return provider;
  }

  private static Flux<Message<?>> messages(final Object... payloads) {
    return Flux.fromArray(payloads).map(p -> DefaultMessage.create(UUID.randomUUID(), p));
  }

  private static Map<String, Object> config(final Object... extra) {
    final Map<String, Object> config =
        new java.util.concurrent.ConcurrentHashMap<>(Map.of("target", "inbox", "title", "Done"));
    for (int i = 0; i < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return config;
  }

  @Test
  void eachMessageRaisesOneNotificationWithTheTextAsItsBody() {
    StepVerifier.create(
            terminal(true)
                .consume(
                    messages(Map.of("text", "first"), "second"),
                    config("severity", "warn", "audience", List.of("role:ADMIN", "key:arun"))))
        .verifyComplete();

    assertThat(raised).extracting(NotificationSpec::body).containsExactly("first", "second");
    assertThat(raised)
        .allSatisfy(s -> {
          assertThat(s.title()).isEqualTo("Done");
          assertThat(s.severity()).isEqualTo(NotificationSeverity.WARN);
          assertThat(s.audience()).containsExactly("role:ADMIN", "key:arun");
          assertThat(s.channels()).isEmpty();
        });
  }

  @Test
  void fixedTextAndCommaSeparatedAudienceAndNoUrlNeeded() {
    StepVerifier.create(
            terminal(true)
                .consume(
                    messages("x"), config("text", "fixed", "audience", "role:VIEWER, key:sam")))
        .verifyComplete();

    assertThat(raised.getFirst().body()).isEqualTo("fixed");
    assertThat(raised.getFirst().audience()).containsExactly("role:VIEWER", "key:sam");
    assertThat(raised.getFirst().severity()).isEqualTo(NotificationSeverity.INFO);
  }

  @Test
  void gatewayFailureFailsTheNodeOrWithContinueRoutesTheError() {
    fail = true;

    StepVerifier.create(terminal(true).consume(messages("x"), config()))
        .expectError(IllegalStateException.class)
        .verify();
    StepVerifier.create(terminal(true).consume(messages("x"), config("failureMode", "CONTINUE")))
        .verifyComplete();
  }

  @Test
  void withoutGatewayBeanTheInboxTargetIsAnError() {
    StepVerifier.create(terminal(false).consume(messages("x"), config()))
        .expectError(IllegalStateException.class)
        .verify();
    final NotifyTerminal emptyProvider = terminal(false);
    emptyProvider.setNotificationGateway(provider(null));
    StepVerifier.create(emptyProvider.consume(messages("x"), config()))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void validationRequiresTitleAndKnownSeverityAndSelectorsButNoUrl() {
    final NotifyTerminal terminal = terminal(true);

    StepVerifier.create(terminal.validateConfig(config())).verifyComplete();
    StepVerifier.create(terminal.validateConfig(Map.of("target", "inbox")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(terminal.validateConfig(config("severity", "LOUD")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(terminal.validateConfig(config("audience", "supervisor:*")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            terminal.validateConfig(Map.of("target", "carrier-pigeon", "url", "https://x.example")))
        .expectError(IllegalArgumentException.class)
        .verify();
    assertThatThrownBy(() -> terminal.consume(messages("x"), Map.of("target", "inbox")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void webhookConfigurationIsUnchangedWhetherOrNotTargetIsGiven() {
    final NotifyTerminal terminal = terminal(true);

    StepVerifier.create(terminal.validateConfig(Map.of("url", "https://hooks.example.com/x")))
        .verifyComplete();
    StepVerifier.create(
            terminal.validateConfig(
                Map.of("target", "WEBHOOK", "url", "https://hooks.example.com/x")))
        .verifyComplete();
    StepVerifier.create(terminal.validateConfig(Map.of("target", "webhook")))
        .expectError(IllegalArgumentException.class)
        .verify();
    assertThat(raised).isEmpty();
  }
}
