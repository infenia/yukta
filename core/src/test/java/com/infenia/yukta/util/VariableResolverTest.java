// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.store.SecretProvider;
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
}
