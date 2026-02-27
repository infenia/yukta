/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.SecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@ExtendWith(MockitoExtension.class)
class VariableResolverTest {

  @Mock private SecretProvider secretProvider;
  private VariableResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new VariableResolver(secretProvider);
  }

  @Test
  void testIsStatic() {
    assertTrue(resolver.isStatic("plain string"));
    assertTrue(resolver.isStatic(123));
    assertFalse(resolver.isStatic("${env.USER}"));
    assertFalse(resolver.isStatic("decrypted:SECRET"));
    assertFalse(resolver.isStatic("Hello ${name}"));
  }

  @Test
  void testResolveStatic() {
    StepVerifier.create(resolver.resolve("plain")).expectNext("plain").verifyComplete();
    StepVerifier.create(resolver.resolve(123)).expectNext(123).verifyComplete();
  }

  @Test
  void testResolveEnv() {
    // Assuming USER or PATH exists in the environment
    String user = System.getenv("USER");
    if (user != null) {
      StepVerifier.create(resolver.resolve("${env.USER}")).expectNext(user).verifyComplete();
    }
  }

  @Test
  void testResolveSys() {
    System.setProperty("test.prop", "test-val");
    StepVerifier.create(resolver.resolve("${sys.test.prop}"))
        .expectNext("test-val")
        .verifyComplete();
  }

  @Test
  void testResolveContext() {
    StepVerifier.create(
            resolver
                .resolve("${context.workflowId}")
                .contextWrite(Context.of("workflowId", "wf-123")))
        .expectNext("wf-123")
        .verifyComplete();
  }

  @Test
  void testResolveInterpolation() {
    System.setProperty("name", "Yukta");
    StepVerifier.create(resolver.resolve("Hello ${sys.name}!"))
        .expectNext("Hello Yukta!")
        .verifyComplete();
  }

  @Test
  void testResolveCasting() {
    System.setProperty("port", "8080");
    StepVerifier.create(resolver.resolve("${sys.port:int}")).expectNext(8080).verifyComplete();

    System.setProperty("enabled", "true");
    StepVerifier.create(resolver.resolve("${sys.enabled:bool}")).expectNext(true).verifyComplete();
  }

  @Test
  void testResolveSecret() {
    when(secretProvider.getSecret("my-key")).thenReturn(Mono.just("secret-value"));
    StepVerifier.create(resolver.resolve("decrypted:my-key"))
        .expectNext("secret-value")
        .verifyComplete();
  }

  @Test
  void testResolveInterpolatedSecret() {
    when(secretProvider.getSecret("db-pass")).thenReturn(Mono.just("p@ss"));
    StepVerifier.create(resolver.resolve("Pass is ${decrypted:db-pass}"))
        .expectNext("Pass is p@ss")
        .verifyComplete();
  }

  @Test
  void testSecretBlacklist() {
    StepVerifier.create(resolver.resolve("${env.DB_PASSWORD}"))
        .expectError(SecurityException.class)
        .verify();

    StepVerifier.create(resolver.resolve("${sys.MY_SECRET_KEY}"))
        .expectError(SecurityException.class)
        .verify();
  }

  @Test
  void testBlacklistButDecryptedAllowed() {
    when(secretProvider.getSecret("DB_PASSWORD")).thenReturn(Mono.just("safe"));
    StepVerifier.create(resolver.resolve("decrypted:DB_PASSWORD"))
        .expectNext("safe")
        .verifyComplete();
  }
}
