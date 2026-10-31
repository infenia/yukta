// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import com.infenia.yukta.console.ConsoleLogs;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
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
      Usage: yukta [start|stop|status|restart|logs [-f]] [--version] [--help] [--mode=console|plain|daemon] [--plain]
                   [--<spring.property>=value ...]

      Starts the Yukta server. Any other argument is a Spring Boot property.

      Commands (background process; state in $YUKTA_HOME or $XDG_STATE_HOME/yukta):
        start     run the server detached, wait until it is healthy
        stop      drain, then SIGTERM, then SIGKILL after a timeout
        status    running or not (exit 3), pid, URL, health
        restart   stop, then start
        logs [-f] show the last lines of the log, -f to follow

      Run modes (--mode, or YUKTA_MODE; --plain is --mode=plain):
        console   interactive dashboard; the default on a terminal
        plain     log lines on stdout; the default when not on a terminal
        daemon    background process (managed by yukta start|stop|status)

      Environment variables:
        YUKTA_MODE             run mode, as --mode
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

    if (Daemon.handles(args)) {
      System.exit(runDaemonCommand(args));
    }

    Hooks.enableAutomaticContextPropagation();

    if (isNativeImage() && !hasProfileArgument(args)) {
      System.setProperty("spring.profiles.active", "prod");
    }

    final RunMode mode = RunMode.resolve(System.getenv(), RunMode.isTty(), args);
    System.setProperty("yukta.run-mode", mode.name().toLowerCase(Locale.ROOT));

    final SpringApplication app = new SpringApplication(YuktaApplication.class);
    if (mode == RunMode.CONSOLE) {
      // the dashboard owns the screen: logs go to its ring buffer; if startup fails, print them
      app.addListeners(
          (ApplicationListener<ApplicationPreparedEvent>) e -> ConsoleLogs.install(),
          (ApplicationListener<ApplicationFailedEvent>)
              e -> ConsoleLogs.restore().forEach(System.err::println));
    }
    app.run(args);
  }

  private static int runDaemonCommand(final String... args) {
    try {
      return new Daemon(Daemon.homeFrom(System.getenv()), System.getenv(), System.out).run(args);
    } catch (IOException e) {
      System.err.println("yukta " + args[0] + " failed: " + e);
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 1;
    }
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
    return profileChosen(System.getenv(), args);
  }

  /**
   * Whether the operator picked a profile: a command-line argument, or the {@code
   * SPRING_PROFILES_ACTIVE} variable (the system property main() would set beats the variable, so
   * it must not be set over it).
   */
  /* default */ static boolean profileChosen(final Map<String, String> env, final String... args) {
    boolean retValue = env.containsKey("SPRING_PROFILES_ACTIVE");
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
