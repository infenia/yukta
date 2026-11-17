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

  /** Provider of the on-disk stores. */
  private static final String FILES =
      "com.infenia.yukta.service.sessionstore.FileSessionConfigStore";

  /** Provider of the PostgreSQL stores and the leader lease. */
  private static final String POSTGRES =
      "com.infenia.yukta.persistence.postgres.PostgresPersistenceAutoConfiguration";

  /** Provider of remote workers. */
  private static final String WORKERS = "com.infenia.workers.WorkersAutoConfiguration";

  /** Build hint for the on-disk stores. */
  private static final String FILES_HINT = "-Pyukta.edition=plus";

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
          Set.of(IN_MEMORY_TYPE, "file", POSTGRES_TYPE),
          "yukta.restart.store-type",
          Set.of("memory", IN_MEMORY_TYPE, "file", POSTGRES_TYPE),
          "yukta.input.store-type",
          Set.of("memory", IN_MEMORY_TYPE, POSTGRES_TYPE),
          "yukta.agents.store-type",
          Set.of("memory", IN_MEMORY_TYPE, POSTGRES_TYPE),
          "yukta.ha.mode",
          Set.of("single", POSTGRES_TYPE));

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
          new Gate(SESSION_TYPE, "file", FILES, Edition.PLUS, FILES_HINT),
          new Gate("yukta.execution-log.dir", null, FILES, Edition.PLUS, FILES_HINT),
          new Gate("yukta.restart.dir", null, FILES, Edition.PLUS, FILES_HINT),
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
   * @param value the value that needs the module, or null when any non-blank value does (a
   *     directory setting is not gated when its store type is {@code postgres})
   * @param provider a class that exists only when the module is in the build
   * @param edition the edition that has the module
   * @param hint how to build it
   */
  /* default */ record Gate(
      String key, @Nullable String value, String provider, Edition edition, String hint) {

    private static @Nullable String strip(@Nullable final String s) {
      return s == null ? null : s.strip();
    }

    /* default */ boolean isUsed(final Environment environment) {
      final String actual = environment.getProperty(key);
      final boolean used;
      if (value != null) {
        // like @ConditionalOnProperty: case does not matter, and neither do stray blanks
        used = value.equalsIgnoreCase(strip(actual));
      } else if (StringUtils.hasText(actual)) {
        used =
            !key.endsWith(".dir")
                || !POSTGRES_TYPE.equalsIgnoreCase(strip(environment.getProperty(storeType())));
      } else {
        used = false;
      }
      return used;
    }

    private String storeType() {
      return key.substring(0, key.length() - ".dir".length()) + ".store-type";
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

  private static boolean isPostgres(final Environment environment, final String key) {
    final String value = environment.getProperty(key);
    return value != null && POSTGRES_TYPE.equalsIgnoreCase(value.strip());
  }
}
