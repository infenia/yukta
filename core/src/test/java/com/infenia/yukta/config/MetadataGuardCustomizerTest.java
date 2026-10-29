// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** Tests for {@link MetadataGuardCustomizer}. */
@NoArgsConstructor
class MetadataGuardCustomizerTest {

  @Test
  void customizeGivesBuilderConnectorAndStillBuilds() {
    final WebClient.Builder builder = WebClient.builder();

    new MetadataGuardCustomizer().customize(builder);

    assertThat(builder.build()).isNotNull();
  }
}
