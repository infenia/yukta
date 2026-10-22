// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

/**
 * Main application class for Yukta. Yukta is a Spring Boot application that manages external
 * projects and runs quality checks.
 */
@SpringBootApplication
@SuppressWarnings("PMD.UseUtilityClass")
public class YuktaApplication {

  private static final String HELP =
      """
      Usage: yukta [--version] [--help] [--<spring.property>=value ...]

      Starts the Yukta server. Any other argument is a Spring Boot property.

      Environment variables:
        YUKTA_API_KEY          API key required by the REST API
        YUKTA_SERVER_ADDRESS   address to bind (default: loopback)
        YUKTA_BOOTSTRAP_DIR    directory of workflows loaded at start\
      """;

  /**
   * Main method to start the application.
   *
   * @param args command line arguments
   */
  public static void main(final String[] args) {
    final String info = infoOutput(args);
    if (info != null) {
      System.out.println(info);
      return;
    }

    Hooks.enableAutomaticContextPropagation();

    if (isNativeImage() && !hasProfileArgument(args)) {
      System.setProperty("spring.profiles.active", "prod");
    }

    final SpringApplication app = new SpringApplication(YuktaApplication.class);
    app.run(args);
  }

  /** Text for --version or --help, or null when the server should start. */
  static String infoOutput(final String... args) {
    String out = null;
    for (final String arg : args) {
      if ("--version".equals(arg)) {
        out = "yukta version " + version();
        break;
      }
      if ("--help".equals(arg) || "-h".equals(arg)) {
        out = HELP;
        break;
      }
    }
    return out;
  }

  private static String version() {
    final Properties props = new Properties();
    try (InputStream in =
        YuktaApplication.class.getResourceAsStream("/META-INF/build-info.properties")) {
      if (in != null) {
        props.load(in);
      }
    } catch (IOException e) {
      // fall through to "unknown"
    }
    return props.getProperty("build.version", "unknown");
  }

  private static boolean isNativeImage() {
    final String nativeImageProp = System.getProperty("org.graalvm.nativeimage.imagecode");
    return "runtime".equals(nativeImageProp);
  }

  private static boolean hasProfileArgument(final String... args) {
    boolean retValue = false;
    for (final String arg : args) {
      if (arg.startsWith("--spring.profiles.active=")
          || arg.startsWith("-Dspring.profiles.active=")) {
        retValue = true;
        break;
      }
    }
    return retValue;
  }
}
