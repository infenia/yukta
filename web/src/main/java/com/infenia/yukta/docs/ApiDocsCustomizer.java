// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.docs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Objects;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/** Describes the REST API: its title and version and how it is authenticated. */
@Component
public class ApiDocsCustomizer implements OpenApiCustomizer {

  private static final String DESCRIPTION =
      "Create sessions and workflows, start and steer executions, and read their status, logs and"
          + " results. Alpha: paths and schemas may change before v0.1.0.";

  private final ObjectProvider<BuildProperties> build;

  /**
   * Reads the version from the build information, when the build provides it.
   *
   * @param build the build's version, when the build provides build information
   */
  public ApiDocsCustomizer(final ObjectProvider<BuildProperties> build) {
    this.build = build;
  }

  /** Adds the title, version and authentication. */
  @Override
  public void customise(final OpenAPI openApi) {
    final BuildProperties buildInfo = build.getIfAvailable();
    final String version = buildInfo != null ? buildInfo.getVersion() : "unknown";
    openApi.setInfo(new Info().title("Yukta REST API").version(version).description(DESCRIPTION));
    openApi.components(
        Objects.requireNonNullElseGet(openApi.getComponents(), Components::new)
            .addSecuritySchemes(
                "bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer"))
            .addSecuritySchemes(
                "apiKeyAuth",
                new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name("X-API-Key")));
    openApi.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    openApi.addSecurityItem(new SecurityRequirement().addList("apiKeyAuth"));
  }
}
