// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.ai.AiBackend;
import com.infenia.yukta.plugin.core.ConfigField;
import java.util.List;
import java.util.Objects;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Tests for {@link AiBackendController}. */
@NoArgsConstructor
class AiBackendControllerTest {

  private static AiBackend backend(final String kind) {
    return new AiBackend() {
      @Override
      public String kind() {
        return kind;
      }

      @Override
      public String description() {
        return "d";
      }

      @Override
      public List<ConfigField> options() {
        return List.of();
      }
    };
  }

  @Test
  void list_returnsEachBackendAsInfo() {
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("a", backend("A"));

    final var body =
        Objects.requireNonNull(
            new AiBackendController(beans.getBeanProvider(AiBackend.class)).list().block());

    assertThat(Objects.requireNonNull(body.getBody()).data())
        .containsExactly(new AiBackend.Info("A", "d", List.of()));
  }

  @Test
  void list_isEmptyWhenNoPluginOffersBackends() {
    final var body =
        Objects.requireNonNull(
            new AiBackendController(
                    new StaticListableBeanFactory().getBeanProvider(AiBackend.class))
                .list()
                .block());

    assertThat(Objects.requireNonNull(body.getBody()).data()).isEmpty();
  }
}
