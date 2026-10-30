// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

class ApiDocsCustomizerTest {

  @Test
  void aliasesOfTheExecutionRoutesAreDeprecatedAndCurrentRoutesAreNot() {
    final OpenAPI api = new OpenAPI().paths(new Paths());
    api.getPaths().addPathItem("/api/workflow/executions/{executionId}/stop", post());
    api.getPaths().addPathItem("/api/workflow/start", post());
    api.getPaths().addPathItem("/api/workflow/{sessionId}/history", get());
    api.getPaths().addPathItem("/api/workflow/{sessionId}/{workflowId}/stop", post());
    api.getPaths().addPathItem("/api/executions/{executionId}/stop", post());

    new ApiDocsCustomizer(noBuildInfo()).customise(api);

    assertThat(
            api.getPaths()
                .get("/api/workflow/executions/{executionId}/stop")
                .getPost()
                .getDeprecated())
        .isTrue();
    assertThat(api.getPaths().get("/api/workflow/start").getPost().getDeprecated())
        .isNotEqualTo(true);
    assertThat(api.getPaths().get("/api/workflow/{sessionId}/history").getGet().getDeprecated())
        .isNotEqualTo(true);
    assertThat(
            api.getPaths()
                .get("/api/workflow/{sessionId}/{workflowId}/stop")
                .getPost()
                .getDeprecated())
        .isNotEqualTo(true);
    assertThat(api.getPaths().get("/api/executions/{executionId}/stop").getPost().getDeprecated())
        .isNotEqualTo(true);
  }

  @Test
  void describesTheApiAndItsAuthentication() {
    final OpenAPI api = new OpenAPI().paths(new Paths());

    new ApiDocsCustomizer(noBuildInfo()).customise(api);

    assertThat(api.getInfo().getTitle()).isEqualTo("Yukta REST API");
    assertThat(api.getInfo().getVersion()).isEqualTo("unknown");
    assertThat(api.getComponents().getSecuritySchemes()).containsKeys("bearerAuth", "apiKeyAuth");
    assertThat(api.getSecurity()).hasSize(2);
  }

  @Test
  void theVersionComesFromTheBuildWhenItIsKnown() {
    final Properties properties = new Properties();
    properties.setProperty("version", "1.2.3");
    final BuildProperties build = new BuildProperties(properties);
    final OpenAPI api = new OpenAPI().paths(new Paths());

    new ApiDocsCustomizer(provider(build)).customise(api);

    assertThat(api.getInfo().getVersion()).isEqualTo("1.2.3");
  }

  private static PathItem post() {
    return new PathItem().post(new Operation());
  }

  private static PathItem get() {
    return new PathItem().get(new Operation());
  }

  private static ObjectProvider<BuildProperties> noBuildInfo() {
    return provider(null);
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<BuildProperties> provider(final BuildProperties build) {
    final ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(build);
    return provider;
  }
}
