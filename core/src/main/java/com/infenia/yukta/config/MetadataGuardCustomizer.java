// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import lombok.NoArgsConstructor;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** Gives every WebClient the {@link MetadataGuardResolver}. */
@NoArgsConstructor
class MetadataGuardCustomizer implements WebClientCustomizer {

  @Override
  public void customize(final WebClient.Builder builder) {
    // ponytail: replaces the auto-configured connector (shared Reactor Netty resources, custom
    // timeouts); copy those onto this HttpClient if a deployment sets them
    builder.clientConnector(
        new ReactorClientHttpConnector(
            HttpClient.create().resolver(MetadataGuardResolver.INSTANCE)));
  }
}
