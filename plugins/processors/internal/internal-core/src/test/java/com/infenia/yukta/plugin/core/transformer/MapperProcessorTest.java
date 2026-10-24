// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.transformer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.jknack.handlebars.Template;
import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.AtLeastOneConstructor",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CommentRequired",
  "PMD.ShortVariable",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestAssertionsShouldIncludeMessage",
  "PMD.UnnecessaryFullyQualifiedName",
  "PMD.UseConcurrentHashMap",
  "unchecked"
})
@ExpectedErrorLog("ERROR com.infenia.yukta.plugin.core.transformer.MapperProcessor - ")
class MapperProcessorTest {

  private final ScriptEngine engine = mock(ScriptEngine.class);
  private final ObjectProvider<ScriptEngine> provider = mock(ObjectProvider.class);
  private final MapperProcessor withEngine = new MapperProcessor(provider);

  private static Message<?> msg(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  private static Object run(
      final MapperProcessor p, final Map<String, Object> cfg, final Object in) {
    return p.process(Flux.just(msg(in)), cfg).map(Message::payload).blockFirst();
  }

  private static Map<String, Object> cfg(final Object... kv) {
    final Map<String, Object> m = new HashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  // ---- script engine wiring / validation ----

  @Test
  void scriptModeIsRejectedWithoutScriptEngine() {
    final var mapper = new MapperProcessor();

    StepVerifier.create(mapper.validateConfig(Map.of("mode", "SCRIPT", "mapping", "1")))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("script-js"))
        .verify();
  }

  @Test
  void projectionModeStillValidatesWithoutScriptEngine() {
    StepVerifier.create(
            new MapperProcessor().validateConfig(Map.of("mode", "PROJECTION", "mapping", Map.of())))
        .verifyComplete();
  }

  @Test
  void providerWithoutEngineIsRejectedToo() {
    when(provider.getIfAvailable()).thenReturn(null);
    StepVerifier.create(withEngine.validateConfig(Map.of("mode", "SCRIPT", "mapping", "1")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void validateConfigBranches() {
    when(provider.getIfAvailable()).thenReturn(engine);
    StepVerifier.create(withEngine.validateConfig(Map.of("mapping", "1"))).expectError().verify();
    StepVerifier.create(withEngine.validateConfig(Map.of("mode", "NOPE", "mapping", "1")))
        .expectError()
        .verify();
    StepVerifier.create(withEngine.validateConfig(Map.of("mode", "TEMPLATE")))
        .expectError()
        .verify();
    StepVerifier.create(withEngine.validateConfig(Map.of("mode", "SCRIPT", "mapping", "1")))
        .verifyComplete();
    StepVerifier.create(withEngine.validateConfig(Map.of("mode", "TEMPLATE", "mapping", "x")))
        .verifyComplete();
  }

  @Test
  void describeTypeAndHeaviness() {
    assertThat(withEngine.getType()).isEqualTo("MAPPER");
    assertThat(withEngine.describe().uiDesign()).isPresent();
    assertThat(withEngine.isComputationallyHeavy(Map.of("mode", "SCRIPT"))).isTrue();
    assertThat(withEngine.isComputationallyHeavy(Map.of("mode", "TEMPLATE"))).isFalse();
    assertThat(withEngine.isComputationallyHeavy(Map.of())).isFalse();
  }

  @Test
  void validateInContextWarnsOnlyForSimpleScripts() {
    final WorkflowContext ctx = new WorkflowContext("n", List.of(), List.of());
    for (final String script :
        List.of("1+1", "x".repeat(60), "if (a) b", "for (;;) {}", "function f(){}", "while(1){}")) {
      StepVerifier.create(
              withEngine.validateInContext(ctx, Map.of("mode", "SCRIPT", "mapping", script)))
          .verifyComplete();
    }
    StepVerifier.create(
            withEngine.validateInContext(ctx, Map.of("mode", "SCRIPT", "mapping", Map.of())))
        .verifyComplete();
    StepVerifier.create(
            withEngine.validateInContext(ctx, Map.of("mode", "PROJECTION", "mapping", "1+1")))
        .verifyComplete();
  }

  // ---- initialize ----

  @Test
  void initializeBranches() {
    when(provider.getIfAvailable()).thenReturn(engine);
    StepVerifier.create(
            withEngine.initialize(
                Map.of("mode", "PROJECTION", "mapping", Map.of("a", "payload.a"))))
        .verifyComplete();
    StepVerifier.create(withEngine.initialize(Map.of("mode", "PROJECTION", "mapping", "oops")))
        .verifyComplete();
    StepVerifier.create(
            withEngine.initialize(Map.of("mode", "TEMPLATE", "mapping", Map.of("a", "{{x}}"))))
        .verifyComplete();
    StepVerifier.create(withEngine.initialize(Map.of("mode", "TEMPLATE", "mapping", "{{x}}")))
        .verifyComplete();
    StepVerifier.create(withEngine.initialize(Map.of("mode", "TEMPLATE", "mapping", 5)))
        .verifyComplete();
    StepVerifier.create(withEngine.initialize(Map.of("mode", "SCRIPT", "mapping", "1")))
        .verifyComplete();
    StepVerifier.create(withEngine.initialize(Map.of("mode", "SCRIPT", "mapping", 1)))
        .verifyComplete();
    verify(engine).precompile("1");
  }

  @Test
  void initializeScriptWithoutEngineFails() {
    assertThatThrownBy(
            () -> new MapperProcessor().initialize(Map.of("mode", "SCRIPT", "mapping", "1")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void initializeInvalidTemplateFails() {
    assertThatThrownBy(
            () -> withEngine.initialize(Map.of("mode", "TEMPLATE", "mapping", "{{#if}}")))
        .hasMessageContaining("Failed to compile");
  }

  // ---- PROJECTION ----

  @Test
  void projectionDropOriginalAndKeepOriginal() {
    final Map<String, String> mapping = Map.of("out.name", "payload['name']");
    final Map<String, Object> in = Map.of("name", "n", "other", 1);

    final Map<String, Object> dropped =
        (Map<String, Object>) run(withEngine, cfg("mode", "PROJECTION", "mapping", mapping), in);
    assertThat(dropped).containsOnlyKeys("out");

    final Map<String, Object> kept =
        (Map<String, Object>)
            run(
                withEngine,
                cfg("mode", "PROJECTION", "mapping", mapping, "dropOriginal", false),
                in);
    assertThat(kept).containsKeys("out", "other");
  }

  @Test
  void projectionNullValueAndFailureHandling() {
    final Map<String, String> nullMapping = Map.of("a", "null");
    assertThat(
            (Map<String, Object>)
                run(
                    withEngine,
                    cfg("mode", "PROJECTION", "mapping", nullMapping, "strictMode", false),
                    Map.of("x", 1)))
        .isEmpty();
    assertThat(
            (Map<String, Object>)
                run(withEngine, cfg("mode", "PROJECTION", "mapping", nullMapping), Map.of("x", 1)))
        .containsKey("a");

    final Map<String, Object> bad = cfg("mode", "PROJECTION", "mapping", Map.of("a", "1/0"));
    assertThat(
            (Map<String, Object>)
                run(
                    withEngine,
                    cfg("mode", "PROJECTION", "mapping", Map.of("a", "1/0"), "strictMode", false),
                    "s"))
        .isEmpty();
    assertThatThrownBy(() -> run(withEngine, bad, "s")).hasMessageContaining("Mapping failed");
  }

  @Test
  void unsupportedModeFails() {
    assertThatThrownBy(() -> run(withEngine, cfg("mode", "NOPE", "mapping", "x"), "s"))
        .hasMessageContaining("Unsupported Mapper mode");
  }

  // ---- TEMPLATE ----

  @Test
  void templateMapAndString() {
    final Map<String, Object> mapCfg =
        cfg("mode", "TEMPLATE", "mapping", Map.of("greeting", "hi {{payload.name}}"));
    withEngine.initialize(mapCfg).block();
    assertThat((Map<String, Object>) run(withEngine, mapCfg, Map.of("name", "bob")))
        .containsEntry("greeting", "hi bob");

    final Map<String, Object> keepCfg =
        cfg(
            "mode",
            "TEMPLATE",
            "mapping",
            Map.of("g", "hi {{payload.name}}"),
            "dropOriginal",
            false);
    withEngine.initialize(keepCfg).block();
    assertThat((Map<String, Object>) run(withEngine, keepCfg, Map.of("name", "bob")))
        .containsKeys("g", "name");

    final Map<String, Object> strCfg = cfg("mode", "TEMPLATE", "mapping", "yo {{payload}}");
    withEngine.initialize(strCfg).block();
    assertThat(run(withEngine, strCfg, "bob")).isEqualTo("yo bob");
  }

  @Test
  void templateInvalidMappingType() {
    assertThatThrownBy(() -> run(withEngine, cfg("mode", "TEMPLATE", "mapping", 5), "s"))
        .hasMessageContaining("Invalid mapping for TEMPLATE");
  }

  @Test
  void templateNotCompiledByInitializeIsCompiledOnDemand() {
    assertThat(
            (Map<String, Object>)
                run(withEngine, cfg("mode", "TEMPLATE", "mapping", Map.of("a", "{{x}}")), "s"))
        .containsEntry("a", "");
    assertThat(run(withEngine, cfg("mode", "TEMPLATE", "mapping", "{{x}}"), "s")).isEqualTo("");
    assertThatThrownBy(
            () -> run(withEngine, cfg("mode", "TEMPLATE", "mapping", Map.of("a", "{{#if}}")), "s"))
        .hasMessageContaining("Failed to compile Handlebars template");
    // a template that cannot be compiled is skipped, or for a single template gives an empty text
    assertThat(
            (Map<String, Object>)
                run(
                    withEngine,
                    cfg("mode", "TEMPLATE", "mapping", Map.of("a", "{{#if}}"), "strictMode", false),
                    "s"))
        .isEmpty();
    assertThatThrownBy(() -> run(withEngine, cfg("mode", "TEMPLATE", "mapping", "{{#if}}"), "s"))
        .hasMessageContaining("Failed to compile Handlebars template");
    assertThat(
            run(
                withEngine,
                cfg("mode", "TEMPLATE", "mapping", "{{#if}}", "strictMode", false),
                "s"))
        .isEqualTo("");
  }

  @Test
  void templateCacheIsBoundedAndEvictedTemplatesAreCompiledAgain() {
    final Map<String, String> mapping = new LinkedHashMap<>();
    for (int i = 0; i < 1_300; i++) {
      mapping.put("k" + i, "v" + i + "-{{payload.x}}");
    }
    final Map<String, Object> config = cfg("mode", "TEMPLATE", "mapping", mapping);

    StepVerifier.create(withEngine.initialize(config)).verifyComplete();

    assertThat(withEngine.templateCacheSize()).isLessThanOrEqualTo(1_000L);
    assertThat((Map<String, Object>) run(withEngine, config, Map.of("x", "!")))
        .hasSize(1_300)
        .containsEntry("k0", "v0-!")
        .containsEntry("k1299", "v1299-!");
  }

  @Test
  void templateIoFailureStrictAndLenient() throws IOException {
    final Template broken = mock(Template.class);
    when(broken.apply(any(Object.class))).thenThrow(new IOException("boom"));
    java.util.Objects.requireNonNull(
            (Cache<String, Template>) ReflectionTestUtils.getField(withEngine, "templateCache"))
        .put("{{x}}", broken);

    final Map<String, Object> mapCfg = cfg("mode", "TEMPLATE", "mapping", Map.of("a", "{{x}}"));
    assertThatThrownBy(() -> run(withEngine, mapCfg, "s"))
        .hasMessageContaining("Template application failed");
    assertThat(
            (Map<String, Object>)
                run(
                    withEngine,
                    cfg("mode", "TEMPLATE", "mapping", Map.of("a", "{{x}}"), "strictMode", false),
                    "s"))
        .isEmpty();

    assertThatThrownBy(() -> run(withEngine, cfg("mode", "TEMPLATE", "mapping", "{{x}}"), "s"))
        .hasMessageContaining("Template application failed");
    assertThat(
            run(withEngine, cfg("mode", "TEMPLATE", "mapping", "{{x}}", "strictMode", false), "s"))
        .isEqualTo("");
  }

  // ---- SCRIPT ----

  @Test
  void scriptDropAndMergeOriginal() {
    when(provider.getIfAvailable()).thenReturn(engine);
    when(engine.eval(anyString(), any())).thenReturn(Map.of("r", 1));

    assertThat(run(withEngine, cfg("mode", "SCRIPT", "mapping", "s"), Map.of("o", 2)))
        .isEqualTo(Map.of("r", 1));
    assertThat(
            (Map<String, Object>)
                run(
                    withEngine,
                    cfg("mode", "SCRIPT", "mapping", "s", "dropOriginal", false),
                    Map.of("o", 2)))
        .containsEntry("o", 2)
        .containsEntry("r", 1);

    when(engine.eval(anyString(), any())).thenReturn("scalar");
    assertThat(
            (Map<String, Object>)
                run(
                    withEngine,
                    cfg("mode", "SCRIPT", "mapping", "s", "dropOriginal", false),
                    Map.of("o", 2)))
        .containsOnlyKeys("o");
  }

  @Test
  void scriptFailureStrictAndLenient() {
    when(provider.getIfAvailable()).thenReturn(engine);
    when(engine.eval(anyString(), any())).thenThrow(new IllegalStateException("bad"));

    assertThatThrownBy(() -> run(withEngine, cfg("mode", "SCRIPT", "mapping", "s"), "p"))
        .hasMessageContaining("bad");
    assertThat(run(withEngine, cfg("mode", "SCRIPT", "mapping", "s", "strictMode", false), "p"))
        .isEqualTo(Map.of());
    assertThat(
            run(
                withEngine,
                cfg("mode", "SCRIPT", "mapping", "s", "strictMode", false, "dropOriginal", false),
                "p"))
        .isEqualTo("p");
  }

  @org.junit.jupiter.api.Test
  void declaresConfigSchema() {
    final var schema = withEngine.getConfigSchema();
    org.junit.jupiter.api.Assertions.assertTrue(
        schema.stream().anyMatch(f -> f.required() && "mapping".equals(f.name())));
  }
}
