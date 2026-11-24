// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.edition.Edition;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Stops start-up with a clear message when a setting needs a module that is not in this build.
 * Without it the build would carry on in memory, or fail later with a missing bean, and the data
 * the user expected to keep would be lost on restart. A setting is refused only when the class that
 * provides it is absent, so a build that has the module is never affected.
 *
 * <p>To gate another setting, add a row to {@link #GATES}. It also refuses a value of a store
 * switch that no store answers to ({@link #STORE_VALUES}): a typo would otherwise select the
 * default silently.
 */
@NoArgsConstructor
public class FeatureGateEnvironmentPostProcessor implements EnvironmentPostProcessor {

  /** The store type value that selects PostgreSQL. */
  private static final String POSTGRES_TYPE = "postgres";

  /** The in-memory store type. */
  private static final String IN_MEMORY_TYPE = "in-memory";

  /** Provider of the PostgreSQL stores and the leader lease. */
  private static final String POSTGRES =
      "com.infenia.yukta.persistence.postgres.PostgresPersistenceAutoConfiguration";

  /** Provider of remote workers. */
  private static final String WORKERS = "com.infenia.workers.WorkersAutoConfiguration";

  /** Build hint for PostgreSQL. */
  private static final String POSTGRES_HINT = "-Pyukta.persistence=postgres";

  /** Build hint for workers. */
  private static final String WORKERS_HINT = "-Pyukta.workers=true";

  /** The switch that turns the PostgreSQL module on. */
  private static final String SESSION_TYPE = "yukta.session.store-type";

  /** The values each store switch accepts (case and blanks do not matter; unset is the default). */
  /* default */ static final Map<String, Set<String>> STORE_VALUES =
      Map.of(
          SESSION_TYPE,
          Set.of(IN_MEMORY_TYPE, POSTGRES_TYPE),
          "yukta.restart.store-type",
          Set.of("memory", IN_MEMORY_TYPE, POSTGRES_TYPE),
          "yukta.input.store-type",
          Set.of("memory", IN_MEMORY_TYPE, POSTGRES_TYPE),
          "yukta.agents.store-type",
          Set.of("memory", IN_MEMORY_TYPE, POSTGRES_TYPE),
          "yukta.ha.mode",
          Set.of("single", POSTGRES_TYPE));

  /**
   * The settings that turned an on-disk store on, which were removed: PostgreSQL is the durable
   * store. Left alone they would keep the data in memory without a word. {@code
   * yukta.session.base-dir} and {@code yukta.execution-log.fsync} did nothing on their own, so they
   * are simply no longer read.
   */
  /* default */ static final List<String> REMOVED_FILE_SETTINGS =
      List.of("yukta.execution-log.dir", "yukta.restart.dir", "yukta.restart.spill-dir");

  /** Switches whose {@code postgres} value only works with PostgreSQL sessions. */
  /* default */ static final List<String> POSTGRES_ONLY =
      List.of(
          "yukta.restart.store-type",
          "yukta.input.store-type",
          "yukta.agents.store-type",
          "yukta.ha.mode");

  /** The gated settings. */
  /* default */ static final List<Gate> GATES =
      List.of(
          new Gate(SESSION_TYPE, POSTGRES_TYPE, POSTGRES, Edition.PLUS, POSTGRES_HINT),
          new Gate(
              "yukta.restart.store-type", POSTGRES_TYPE, POSTGRES, Edition.PLUS, POSTGRES_HINT),
          new Gate("yukta.input.store-type", POSTGRES_TYPE, POSTGRES, Edition.PLUS, POSTGRES_HINT),
          new Gate("yukta.ha.mode", POSTGRES_TYPE, POSTGRES, Edition.PLUS, POSTGRES_HINT),
          new Gate("yukta.workers.token", null, WORKERS, Edition.PLUS, WORKERS_HINT),
          new Gate("yukta.workers.control-plane", null, WORKERS, Edition.PLUS, WORKERS_HINT));

  /**
   * One gated setting.
   *
   * @param key the property
   * @param value the value that needs the module, or null when any non-blank value does
   * @param provider a class that exists only when the module is in the build
   * @param edition the edition that has the module
   * @param hint how to build it
   */
  /* default */ record Gate(
      String key, @Nullable String value, String provider, Edition edition, String hint) {

    /* default */ boolean isUsed(final Environment environment) {
      final String actual = environment.getProperty(key);
      // like @ConditionalOnProperty: case does not matter, and neither do stray blanks
      return value == null ? StringUtils.hasText(actual) : value.equalsIgnoreCase(strip(actual));
    }

    /* default */ String describe() {
      return value == null ? key : key + "=" + value;
    }
  }

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    check(environment, name -> ClassUtils.isPresent(name, null));
  }

  /* default */ static void check(final Environment environment, final Predicate<String> present) {
    final boolean fileSessions =
        "file".equalsIgnoreCase(strip(environment.getProperty(SESSION_TYPE)));
    REMOVED_FILE_SETTINGS.stream()
        .filter(key -> fileSessions || StringUtils.hasText(environment.getProperty(key)))
        .findFirst()
        .ifPresent(key -> {
          throw new IllegalStateException(
              (fileSessions ? SESSION_TYPE + "=file" : key)
                  + " is no longer read: the on-disk stores were removed. Keep state in PostgreSQL"
                  + " (yukta.session.store-type=postgres, built with "
                  + POSTGRES_HINT
                  + "), or remove the setting to keep it in memory.");
        });
    STORE_VALUES.forEach((key, allowed) -> {
      final String value = environment.getProperty(key);
      if (StringUtils.hasText(value) && !allowed.contains(value.strip().toLowerCase(Locale.ROOT))) {
        throw new IllegalStateException(
            key
                + "="
                + value
                + " is not a known value; use one of "
                + new java.util.TreeSet<>(allowed));
      }
    });
    GATES.stream()
        .filter(gate -> gate.isUsed(environment) && !present.test(gate.provider()))
        .findFirst()
        .ifPresent(gate -> {
          throw new IllegalStateException(
              gate.describe()
                  + " needs the Yukta "
                  + gate.edition().getDisplayName()
                  + " build ("
                  + gate.edition().getLicense()
                  + "). Build with "
                  + gate.hint()
                  + ", or remove the setting. See LICENSING.md.");
        });
    // the PostgreSQL stores and the lease are configured only with PostgreSQL sessions: without
    // them these switches would be ignored and the data kept in memory
    if (!isPostgres(environment, SESSION_TYPE)) {
      POSTGRES_ONLY.stream()
          .filter(key -> isPostgres(environment, key))
          .findFirst()
          .ifPresent(key -> {
            throw new IllegalStateException(
                key
                    + "=postgres needs "
                    + SESSION_TYPE
                    + "=postgres, or it is ignored and the data stays in memory.");
          });
    }
  }

  private static @Nullable String strip(@Nullable final String s) {
    return s == null ? null : s.strip();
  }

  private static boolean isPostgres(final Environment environment, final String key) {
    final String value = environment.getProperty(key);
    return value != null && POSTGRES_TYPE.equalsIgnoreCase(value.strip());
  }
}
