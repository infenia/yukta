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
@SuppressWarnings({"PMD.UseUtilityClass", "PMD.SystemPrintln"})
public class YuktaApplication {

  /** Prints the version and exits. */
  private static final String VERSION_FLAG = "--version";

  /** Prints usage and exits. */
  private static final String HELP_FLAG = "--help";

  /** Short form of {@link #HELP_FLAG}. */
  private static final String HELP_SHORT_FLAG = "-h";

  /** Reported when the build does not carry a version. */
  private static final String UNKNOWN_VERSION = "unknown";

  /** Text printed for {@link #HELP_FLAG}. */
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
      // plain stdout on purpose: the logging system is not up yet and the output is for a person
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
  /* default */ static String infoOutput(final String... args) {
    String out = null;
    for (final String arg : args) {
      if (VERSION_FLAG.equals(arg)) {
        out = "yukta version " + version();
        break;
      }
      if (HELP_FLAG.equals(arg) || HELP_SHORT_FLAG.equals(arg)) {
        out = HELP;
        break;
      }
    }
    return out;
  }

  private static String version() {
    return versionFrom(
        YuktaApplication.class.getResourceAsStream("/META-INF/build-info.properties"));
  }

  /** The build version in the given build-info stream, or "unknown" if it cannot be read. */
  /* default */ static String versionFrom(final InputStream stream) {
    final Properties props = new Properties();
    if (stream != null) {
      try (stream) {
        props.load(stream);
      } catch (IOException ignored) {
        // fall through to "unknown"
      }
    }
    return props.getProperty("build.version", UNKNOWN_VERSION);
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
