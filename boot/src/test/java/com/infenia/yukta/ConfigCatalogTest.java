// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the Configuration page of the UI in step with the code: every {@code yukta.*} setting that
 * Spring generates configuration metadata for ({@code @ConfigurationProperties}) must appear in
 * {@code ui/src/features/config/lib/catalog.ts}, as a row or in a row's text. A new setting without
 * a row fails here and names the key. A setting that should not be on the page goes in {@link
 * #NOT_SHOWN} with the reason. Skipped in builds with optional modules, which add settings.
 *
 * <p>Settings that are read only with {@code @Value}, {@code @ConditionalOnProperty} or {@code
 * System.getProperty} have no metadata, so this cannot see them; they are added to the catalog by
 * hand.
 */
@DisabledIfSystemProperty(named = "yukta.edition", matches = ".+")
@DisabledIfSystemProperty(named = "yukta.persistence", matches = ".+")
@DisabledIfSystemProperty(named = "yukta.build.workers", matches = ".+")
@NoArgsConstructor
class ConfigCatalogTest {

  private static final Path CATALOG = Path.of("../ui/src/features/config/lib/catalog.ts");

  /** Settings that are deliberately not on the page, each with why. Empty today. */
  private static final Set<String> NOT_SHOWN = Set.of();

  @Test
  void everyYuktaSettingIsInTheCatalog() throws IOException {
    final String catalog = Files.readString(CATALOG, StandardCharsets.UTF_8);

    final List<String> missing = new ArrayList<>();
    for (final String key : yuktaSettings()) {
      if (!NOT_SHOWN.contains(key) && !listed(catalog, key)) {
        missing.add(key);
      }
    }

    assertThat(missing)
        .as(
            "These settings are not on the Configuration page. Add a row for each to"
                + " ui/src/features/config/lib/catalog.ts, or list it in NOT_SHOWN with the reason")
        .isEmpty();
  }

  @Test
  void thereAreSettingsToCheck() throws IOException {
    assertThat(yuktaSettings()).hasSizeGreaterThan(50).contains("yukta.execution.timeout");
  }

  @Test
  void listed_matchesWholeKeysOnly() {
    final String catalog =
        "{ key: 'yukta.execution.queue-capacity-per-workflow' } 'x.yukta.input.memory-max'";

    assertThat(listed(catalog, "yukta.execution.queue-capacity-per-workflow")).isTrue();
    assertThat(listed(catalog, "yukta.execution.queue-capacity")).isFalse();
    assertThat(listed(catalog, "yukta.input.memory-max")).isFalse();
    assertThat(listed("see `yukta.execution.queue-capacity`.", "yukta.execution.queue-capacity"))
        .isTrue();
  }

  /** Whether the catalog text holds {@code key} as a whole key, not part of a longer one. */
  private static boolean listed(final String catalog, final String key) {
    return Pattern.compile("(?<![\\w.-])" + Pattern.quote(key) + "(?![\\w-])")
        .matcher(catalog)
        .find();
  }

  /** The {@code yukta.*} property names in every configuration metadata file on the classpath. */
  private static Set<String> yuktaSettings() throws IOException {
    final JsonMapper mapper = JsonMapper.builder().build();
    final Set<String> keys = new TreeSet<>();
    for (final URL url :
        Collections.list(
            Thread.currentThread()
                .getContextClassLoader()
                .getResources("META-INF/spring-configuration-metadata.json"))) {
      try (InputStream in = url.openStream()) {
        final JsonNode properties = mapper.readTree(in).get("properties");
        if (properties != null) {
          for (final JsonNode property : properties) {
            final String name = property.get("name").asString();
            if (name.startsWith("yukta.")) {
              keys.add(name);
            }
          }
        }
      }
    }
    return keys;
  }
}
