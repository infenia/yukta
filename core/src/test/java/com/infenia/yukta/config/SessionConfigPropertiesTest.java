// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link SessionConfigProperties}. */
@NoArgsConstructor
class SessionConfigPropertiesTest {

  @Test
  void baseDir_defaultsToTheHiddenFolderInTheUserHome() {
    assertThat(new SessionConfigProperties().getBaseDir())
        .isEqualTo(System.getProperty("user.home") + "/.yukta");
  }

  @Test
  void baseDir_canBeSet() {
    final SessionConfigProperties properties = new SessionConfigProperties();

    properties.setBaseDir("/custom/dir");

    assertThat(properties.getBaseDir()).isEqualTo("/custom/dir");
  }

  @Test
  void equality_followsTheBaseDir() {
    final SessionConfigProperties first = new SessionConfigProperties();
    final SessionConfigProperties second = new SessionConfigProperties();
    first.setBaseDir("/dir1");
    second.setBaseDir("/dir1");

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).hasToString(second.toString());

    second.setBaseDir("/dir2");
    assertThat(first).isNotEqualTo(second);
  }
}
