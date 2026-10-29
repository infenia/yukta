// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.service.worker.WorkerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Mutual TLS between a control plane and a worker, with certificates made by keytool. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class WorkersTlsTest {

  /** Password of every key and trust store. */
  private static final String PASSWORD = "changeit";

  /** The control plane. */
  @Configuration
  @EnableAutoConfiguration
  @Import(WorkerRegistry.class)
  /* default */ static class ControlPlane {
    @Bean
    /* default */ MeterRegistry meters() {
      return new SimpleMeterRegistry();
    }
  }

  private static void keytool(final Path dir, final String... args)
      throws IOException, InterruptedException {
    final List<String> command = new java.util.ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(args));
    final Process process =
        new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertThat(process.waitFor()).as("keytool %s", String.join(" ", args)).isZero();
  }

  /** A key pair in {@code <name>.p12} and its certificate in {@code <name>.crt}. */
  private static void identity(final Path dir, final String name)
      throws IOException, InterruptedException {
    keytool(
        dir,
        "-genkeypair",
        "-alias",
        name,
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-storetype",
        "PKCS12",
        "-keystore",
        name + ".p12",
        "-storepass",
        PASSWORD,
        "-dname",
        "CN=localhost",
        "-ext",
        "SAN=dns:localhost",
        "-validity",
        "2");
    keytool(
        dir,
        "-exportcert",
        "-alias",
        name,
        "-keystore",
        name + ".p12",
        "-storepass",
        PASSWORD,
        "-file",
        name + ".crt");
  }

  /** A trust store {@code <name>.p12} that trusts the certificates of the given identities. */
  private static void trust(final Path dir, final String name, final String... trusted)
      throws IOException, InterruptedException {
    for (final String other : trusted) {
      keytool(
          dir,
          "-importcert",
          "-noprompt",
          "-alias",
          other,
          "-file",
          other + ".crt",
          "-keystore",
          name + ".p12",
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD);
    }
  }

  private static String[] bundle(
      final String bundle, final Path dir, final String keystore, final String truststore) {
    final String prefix = "spring.ssl.bundle.jks." + bundle + ".";
    return new String[] {
      prefix + "keystore.location=file:" + dir.resolve(keystore + ".p12"),
      prefix + "keystore.password=" + PASSWORD,
      prefix + "keystore.type=PKCS12",
      prefix + "truststore.location=file:" + dir.resolve(truststore + ".p12"),
      prefix + "truststore.password=" + PASSWORD,
      prefix + "truststore.type=PKCS12"
    };
  }

  private static ConfigurableApplicationContext controlPlane(final Path dir) {
    final List<String> properties =
        new java.util.ArrayList<>(
            List.of(
                "spring.rsocket.server.port=0",
                "spring.rsocket.server.ssl.bundle=server",
                "spring.rsocket.server.ssl.client-auth=need",
                "yukta.workers.token=secret",
                "yukta.workers.lease=3s"));
    properties.addAll(List.of(bundle("server", dir, "server", "client-trusts")));
    return new SpringApplicationBuilder(ControlPlane.class)
        .web(WebApplicationType.NONE)
        .properties(properties.toArray(String[]::new))
        .run();
  }

  private static ConfigurableApplicationContext worker(
      final Path dir,
      final String port,
      final String name,
      final String keystore,
      final String bundle) {
    final List<String> properties =
        new java.util.ArrayList<>(
            List.of(
                "yukta.workers.control-plane=localhost:" + port,
                "yukta.workers.token=secret",
                "yukta.workers.id=" + name,
                "yukta.workers.plugin-types=UPPER",
                "yukta.workers.lease=3s"));
    if (bundle != null) {
      properties.add("yukta.workers.ssl-bundle=" + bundle);
      properties.addAll(List.of(bundle(bundle, dir, keystore, "server-trusts")));
    }
    return new SpringApplicationBuilder(RemoteNodeWorkerConfig.class)
        .web(WebApplicationType.NONE)
        .properties(properties.toArray(String[]::new))
        .run();
  }

  @Test
  void trustedCertificateRegisters_othersDoNot(@TempDir final Path dir)
      throws IOException, InterruptedException {
    identity(dir, "server");
    identity(dir, "client");
    identity(dir, "rogue");
    trust(dir, "client-trusts", "client");
    trust(dir, "server-trusts", "server");

    try (ConfigurableApplicationContext controlPlane = controlPlane(dir)) {
      final String port = controlPlane.getEnvironment().getProperty("local.rsocket.server.port");
      final WorkerRegistry registry = controlPlane.getBean(WorkerRegistry.class);

      try (ConfigurableApplicationContext good = worker(dir, port, "w-good", "client", "client");
          ConfigurableApplicationContext rogue = worker(dir, port, "w-rogue", "rogue", "rogue");
          ConfigurableApplicationContext plain = worker(dir, port, "w-plain", null, null)) {
        await().atMost(Duration.ofSeconds(30)).until(() -> registry.find("w-good").isPresent());
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).until(() -> true);

        // the certificate the control plane does not trust, and no certificate at all, never
        // register
        assertThat(registry.find("w-rogue")).isEmpty();
        assertThat(registry.find("w-plain")).isEmpty();
        assertThat(registry.list()).extracting("workerId").containsExactly("w-good");
        assertThat(good.isRunning() && rogue.isRunning() && plain.isRunning()).isTrue();
      }
    }
  }
}
