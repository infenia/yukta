// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.util.ClassUtils;

/**
 * The application finds its beans by scanning {@code com.infenia.yukta}, so anything on the class
 * path in that package switches something on. This test pins the set for the default build, so a
 * new bean (or a dependency that brings one) shows up as a diff in review. Refresh the list with
 * {@code ./gradlew :boot:test --tests "*BeanInventoryTest" -Pyukta.bean-inventory.update=true}.
 * Skipped in builds with optional modules.
 */
@DisabledIfSystemProperty(named = "yukta.edition", matches = ".+")
@DisabledIfSystemProperty(named = "yukta.persistence", matches = ".+")
@DisabledIfSystemProperty(named = "yukta.workers", matches = ".+")
@DisabledIfSystemProperty(named = "yukta.plugins", matches = ".+")
@DisabledInAotMode
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class BeanInventoryTest {

  private static final Path LIST = Path.of("src/test/resources/expected-beans.txt");

  @Autowired private ApplicationContext context;

  @Test
  void theBeansUnderOurPackageAreTheCheckedInList() throws IOException {
    final List<String> actual =
        Arrays.stream(context.getBeanDefinitionNames())
            .map(context::getType)
            .filter(type -> type != null)
            .map(type -> ClassUtils.getUserClass(type).getName())
            .filter(name -> name.startsWith("com.infenia.yukta."))
            .sorted()
            .distinct()
            .toList();

    if (Boolean.getBoolean("yukta.bean-inventory.update")) {
      Files.write(LIST, actual, StandardCharsets.UTF_8);
    }

    assertThat(actual).containsExactlyElementsOf(Files.readAllLines(LIST, StandardCharsets.UTF_8));
  }
}
