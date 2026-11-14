// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.mcp.McpClientGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@code run.image} plugins, against a fake container CLI that logs its arguments: {@code image
 * inspect} knows only the image {@code present}, {@code pull} fails for {@code missing}, {@code
 * run} echoes stdin as the payload or sleeps for the image {@code sleepy}.
 */
@DisabledOnOs(OS.WINDOWS)
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.TooManyMethods"})
class ImagePluginsTest {

  private static final String IMG = "IMG";

  @TempDir private Path root;

  private Path calls;
  private Path cli;
  private ProcessExecutorGateway gateway;
  private VariableResolver resolver;

  @BeforeEach
  void setUp() throws IOException {
    calls = root.resolve("calls.log");
    cli = root.resolve("fake-docker");
    Files.writeString(
        cli,
        "#!/bin/sh\n"
            + "echo \"$*\" >> '"
            + calls
            + "'\n"
            + "case \"$1\" in\n"
            + "  image) [ \"$3\" = present ] ;;\n"
            + "  pull) [ \"$2\" != missing ] || { echo 'no such image' >&2; exit 1; } ;;\n"
            + "  run) for a; do img=$a; done\n"
            + "       [ \"$img\" = sleepy ] && exec sleep 30\n"
            + "       printf '{\"payload\":'; cat; printf '}\\n' ;;\n"
            + "esac\n");
    assertThat(cli.toFile().setExecutable(true)).isTrue();
    gateway = new ProcessExecutorGateway();
    resolver = mock(VariableResolver.class);
    when(resolver.resolve(any(), any()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0, Object.class)));
  }

  @Test
  void presentImage_isNotPulled_andRunsInNamedContainers_withOperatorArgs() throws IOException {
    image("present", "10s");
    final ProcessorPlugin plugin =
        (ProcessorPlugin) only(load(Map.of("img", List.of("--network=none"))));

    StepVerifier.create(
            plugin.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "x")), Map.of()))
        .assertNext(out -> assertThat(out.payload().toString()).contains("yukta/message/v1"))
        .verifyComplete();
    assertThat(lines()).hasSize(2).first().isEqualTo("image inspect present");
    assertThat(lines().get(1))
        .matches("run --rm -i --name yukta-img-[0-9a-f-]{36} --network=none present");
  }

  @Test
  void missingImage_isPulledAtStartup_andFailedPullFailsStartup() throws IOException {
    image("absent", "10s");
    load(Map.of());
    assertThat(lines()).containsExactly("image inspect absent", "pull absent");

    image("missing", "10s");
    assertThatThrownBy(() -> load(Map.of()))
        .hasMessageContaining("Plugin IMG: cannot pull missing with " + cli + ": no such image");
  }

  @Test
  void timedOutContainer_isRemoved() throws IOException {
    image("sleepy", "1s");
    final ProcessorPlugin plugin = (ProcessorPlugin) only(load(Map.of()));

    StepVerifier.create(
            plugin.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "x")), Map.of()))
        .expectErrorMessage("Plugin IMG timed out after PT1S")
        .verify(Duration.ofSeconds(20));
    assertThat(lines()).last().asString().matches("rm -f yukta-img-[0-9a-f-]{36}");
  }

  @Test
  void cancelledContainer_isRemoved() throws IOException {
    image("sleepy", "30s");
    final ProcessorPlugin plugin = (ProcessorPlugin) only(load(Map.of()));

    StepVerifier.create(
            plugin.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "x")), Map.of()))
        .expectSubscription()
        .thenAwait(Duration.ofMillis(500))
        .thenCancel()
        .verify();
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> lines().stream().anyMatch(l -> l.startsWith("rm -f yukta-img-")));
  }

  @Test
  void containerArgs_matchTypeIgnoringCaseAndSeparators() {
    final StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("YUKTA_PLUGINS_CONTAINER_ARGS_WORD_COUNT", "--network=none,-m=64m")));
    final Containers containers =
        new Containers("podman", ExternalPluginsAutoConfiguration.containerArgs(env));
    final PluginManifest manifest =
        new PluginManifest(
            "WORD_COUNT",
            com.infenia.yukta.plugin.core.PluginCategory.PROCESSOR,
            "d",
            List.of(),
            List.of("default"),
            Duration.ofSeconds(1),
            List.of(),
            "ghcr.io/acme/wc:1",
            Map.of(),
            "image",
            root);
    assertThat(containers.run(manifest, "n"))
        .containsExactly(
            "podman",
            "run",
            "--rm",
            "-i",
            "--name",
            "n",
            "--network=none",
            "-m=64m",
            "ghcr.io/acme/wc:1");
  }

  @Test
  void removeThatCannotRunTheCli_isLoggedAndCompletes() {
    StepVerifier.create(
            new Containers(root.resolve("no-such-cli").toString(), Map.of()).remove("n", gateway))
        .verifyComplete();
  }

  private void image(final String image, final String timeout) throws IOException {
    final Path dir = Files.createDirectories(root.resolve("plugins").resolve(IMG));
    Files.writeString(
        dir.resolve(ExternalPlugins.MANIFEST),
        "{\"apiVersion\":\"yukta/plugin/v1\",\"type\":\"IMG\",\"category\":\"processor\","
            + "\"description\":\"d\",\"timeout\":\""
            + timeout
            + "\",\"run\":{\"image\":\""
            + image
            + "\"}}");
    Files.deleteIfExists(calls);
  }

  private List<Plugin> load(final Map<String, List<String>> args) {
    return new ExternalPlugins(
            root.resolve("plugins"),
            List.of(IMG),
            gateway,
            resolver,
            1 << 20,
            1000,
            new Containers(cli.toString(), args),
            mock(McpClientGateway.class))
        .plugins();
  }

  private List<String> lines() throws IOException {
    return Files.exists(calls) ? Files.readAllLines(calls) : List.of();
  }

  private static Plugin only(final List<Plugin> plugins) {
    assertThat(plugins).hasSize(1);
    return plugins.get(0);
  }
}
