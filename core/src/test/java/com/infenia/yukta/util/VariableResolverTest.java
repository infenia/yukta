// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.SecretProvider;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Unit tests for {@link VariableResolver}. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class VariableResolverTest {

  /** Mocked secret provider. */
  private SecretProvider secretProvider;

  /** Resolver instance for testing. */
  private VariableResolver resolver;

  @BeforeEach
  void setUp() {
    secretProvider = mock(SecretProvider.class);
    resolver = new VariableResolver(secretProvider);
  }

  @Test
  void testIsStatic() {
    assertThat(resolver.isStatic(123)).isTrue();
    assertThat(resolver.isStatic("static")).isTrue();
    assertThat(resolver.isStatic("${var}")).isFalse();
    assertThat(resolver.isStatic("decrypted:key")).isFalse();
  }

  @Test
  void testResolveLiteral() {
    StepVerifier.create(resolver.resolve(123)).expectNext(123).verifyComplete();
    StepVerifier.create(resolver.resolve("plain")).expectNext("plain").verifyComplete();
  }

  @Test
  void testResolveSecret() {
    when(secretProvider.getSecret("mykey")).thenReturn(Mono.just("secret-val"));
    StepVerifier.create(resolver.resolve("decrypted:mykey"))
        .expectNext("secret-val")
        .verifyComplete();

    when(secretProvider.getSecret("missing")).thenReturn(Mono.empty());
    StepVerifier.create(resolver.resolve("decrypted:missing"))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testResolveExpression() {
    System.setProperty("yukta.test", "prop-val");
    StepVerifier.create(resolver.resolve("${sys.yukta.test}"))
        .expectNext("prop-val")
        .verifyComplete();

    StepVerifier.create(resolver.resolve("${sys.yukta.test:string}"))
        .expectNext("prop-val")
        .verifyComplete();

    // Edge cases for colons
    StepVerifier.create(resolver.resolve("${:nonexistent}"))
        .expectNext(":nonexistent")
        .verifyComplete();
    StepVerifier.create(resolver.resolve("${nonexistent:}"))
        .expectNext("nonexistent:")
        .verifyComplete();
    StepVerifier.create(resolver.resolve("${sys.yukta.test:unknownType}"))
        .expectComplete()
        .verify();
  }

  @Test
  void testResolveTypes() {
    System.setProperty("t.int", "123");
    System.setProperty("t.bool", "true");
    System.setProperty("t.long", "456");
    System.setProperty("t.double", "1.23");

    StepVerifier.create(resolver.resolve("${sys.t.int:int}")).expectNext(123).verifyComplete();
    StepVerifier.create(resolver.resolve("${sys.t.bool:bool}")).expectNext(true).verifyComplete();
    StepVerifier.create(resolver.resolve("${sys.t.long:long}")).expectNext(456L).verifyComplete();
    StepVerifier.create(resolver.resolve("${sys.t.double:double}"))
        .expectNext(1.23)
        .verifyComplete();

    System.setProperty("t.float", "4.56");
    StepVerifier.create(resolver.resolve("${sys.t.float:float}")).expectNext(4.56).verifyComplete();
    StepVerifier.create(resolver.resolve("${sys.t.int:integer}")).expectNext(123).verifyComplete();
    StepVerifier.create(resolver.resolve("${sys.t.bool:boolean}"))
        .expectNext(true)
        .verifyComplete();
  }

  @Test
  void testResolveInterpolation() {
    System.setProperty("p1", "v1");
    StepVerifier.create(resolver.resolve("prefix-${sys.p1}-suffix"))
        .expectNext("prefix-v1-suffix")
        .verifyComplete();

    StepVerifier.create(resolver.resolve("${sys.p1}-suffix"))
        .expectNext("v1-suffix")
        .verifyComplete();

    StepVerifier.create(resolver.resolve("prefix-${sys.p1}"))
        .expectNext("prefix-v1")
        .verifyComplete();
  }

  @Test
  void testSecurityBlacklist() {
    StepVerifier.create(resolver.resolve("${DB_PASSWORD}"))
        .expectError(SecurityException.class)
        .verify();
  }

  @Test
  void testResolveContext() {
    StepVerifier.create(
            resolver.resolve("${context.myVar}").contextWrite(ctx -> ctx.put("myVar", "ctx-val")))
        .expectNext("ctx-val")
        .verifyComplete();
  }

  @Test
  void testResolveEnv() {
    // Hard to set env deterministically but can check prefix
    StepVerifier.create(resolver.resolve("${env.PATH}")).expectNextCount(1).verifyComplete();
  }

  @Test
  void testResolveSecretInExpression() {
    when(secretProvider.getSecret("pass")).thenReturn(Mono.just("p123"));
    StepVerifier.create(resolver.resolve("${decrypted:pass}")).expectNext("p123").verifyComplete();
  }

  @Test
  void testResolveStaticKeyInExpression() {
    StepVerifier.create(resolver.resolve("${my-static-item}"))
        .expectNext("my-static-item")
        .verifyComplete();
  }

  @Test
  void testResolveMissingSysProp() {
    StepVerifier.create(resolver.resolve("${sys.nonexistent.prop}")).expectComplete().verify();
  }

  @Test
  void testResolveMissingEnv() {
    StepVerifier.create(resolver.resolve("${env.NONEXISTENT_VAR}")).expectComplete().verify();
  }

  @Test
  void testResolveMissingContext() {
    StepVerifier.create(resolver.resolve("${context.nonexistent}")).expectComplete().verify();
  }

  // --- message-aware resolution ---

  private static Message<?> testMessage(final Object payload, final Map<String, Object> metadata) {
    return DefaultMessage.create(UUID.randomUUID(), payload).withMetadata(metadata);
  }

  @Test
  void testResolveWholePayload() {
    final Message<?> message = testMessage("payload-value", Map.of());
    StepVerifier.create(resolver.resolve("${payload}", message))
        .expectNext("payload-value")
        .verifyComplete();
  }

  @Test
  void testResolvePayloadField() {
    final Message<?> message = testMessage(Map.of("version", "1.2.3"), Map.of());
    StepVerifier.create(resolver.resolve("${payload.version}", message))
        .expectNext("1.2.3")
        .verifyComplete();
  }

  @Test
  void testResolveNestedPayloadField() {
    final Message<?> message =
        testMessage(Map.of("build", Map.of("artifact", "app.jar")), Map.of());
    StepVerifier.create(resolver.resolve("${payload.build.artifact}", message))
        .expectNext("app.jar")
        .verifyComplete();
  }

  @Test
  void testResolvePayloadFieldCastsToStringByDefault() {
    final Message<?> message = testMessage(Map.of("count", 42), Map.of());
    StepVerifier.create(resolver.resolve("${payload.count}", message))
        .expectNext("42")
        .verifyComplete();
  }

  @Test
  void testResolvePayloadFieldWithTypeSuffixPreservesType() {
    final Message<?> message = testMessage(Map.of("count", 42), Map.of());
    StepVerifier.create(resolver.resolve("${payload.count:int}", message))
        .expectNext(42)
        .verifyComplete();
  }

  @Test
  void testResolveMetadataEntry() {
    final Message<?> message = testMessage("payload", Map.of("executionId", "exec-7"));
    StepVerifier.create(resolver.resolve("${metadata.executionId}", message))
        .expectNext("exec-7")
        .verifyComplete();
  }

  @Test
  void testResolvePayloadInterpolation() {
    final Message<?> message = testMessage(Map.of("version", "2.0"), Map.of());
    StepVerifier.create(resolver.resolve("release-${payload.version}-final", message))
        .expectNext("release-2.0-final")
        .verifyComplete();
  }

  @Test
  void testResolveMissingPayloadFieldIsEmpty() {
    final Message<?> message = testMessage(Map.of("other", "x"), Map.of());
    StepVerifier.create(resolver.resolve("${payload.missing}", message)).expectComplete().verify();
  }

  @Test
  void testResolveMissingMetadataKeyIsEmpty() {
    final Message<?> message = testMessage("payload", Map.of());
    StepVerifier.create(resolver.resolve("${metadata.missing}", message)).expectComplete().verify();
  }

  @Test
  void testResolvePayloadPathOnNonMapPayloadIsEmpty() {
    final Message<?> message = testMessage("plain-string", Map.of());
    StepVerifier.create(resolver.resolve("${payload.field}", message)).expectComplete().verify();
  }

  @Test
  void testResolveNestedPathThroughMissingIntermediateIsEmpty() {
    final Message<?> message = testMessage(Map.of("a", "leaf"), Map.of());
    StepVerifier.create(resolver.resolve("${payload.b.c}", message)).expectComplete().verify();
  }

  @Test
  void testResolveNullWholePayloadIsEmpty() {
    final Message<?> message = testMessage(null, Map.of());
    StepVerifier.create(resolver.resolve("${payload}", message)).expectComplete().verify();
  }

  @Test
  void testResolvePayloadKeyWithoutMessageFallsBackToLiteral() {
    StepVerifier.create(resolver.resolve("${payload.version}", null))
        .expectNext("payload.version")
        .verifyComplete();
  }

  @Test
  void testResolveNonMessageKeyWithMessageFallsBackToLiteral() {
    final Message<?> message = testMessage("payload", Map.of());
    StepVerifier.create(resolver.resolve("${some-static-item}", message))
        .expectNext("some-static-item")
        .verifyComplete();
  }

  @Test
  void testResolveBlockedSensitiveKeyStillBlockedWithMessage() {
    final Message<?> message = testMessage(Map.of("apiKey", "s3cr3t"), Map.of());
    StepVerifier.create(resolver.resolve("${payload.apiKey}", message))
        .expectError(SecurityException.class)
        .verify();
  }
}
