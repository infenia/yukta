// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Makes a process with {@code yukta.workers.control-plane} start only what a worker needs: no
 * database, no MCP server. It has no web server unless {@code management.server.port} is set, and
 * then the server serves health and metrics only. Settings the operator made are never replaced.
 */
@NoArgsConstructor
public class WorkerRoleEnvironment implements EnvironmentPostProcessor {

  /** Name of the property source added. */
  /* default */ static final String SOURCE = "yukta-worker-role";

  /** Auto-configurations a worker does not need. */
  private static final String EXCLUDED =
      String.join(
          ",",
          "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
          "org.springframework.boot.r2dbc.autoconfigure.R2dbcInitializationAutoConfiguration",
          "org.springframework.boot.r2dbc.autoconfigure.R2dbcProxyAutoConfiguration",
          "org.springframework.boot.r2dbc.autoconfigure.R2dbcTransactionManagerAutoConfiguration",
          "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration",
          "org.springframework.boot.data.r2dbc.autoconfigure."
              + "DataR2dbcRepositoriesAutoConfiguration",
          "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
          "com.infenia.yukta.persistence.postgres.PostgresPersistenceAutoConfiguration",
          "org.springframework.ai.mcp.server.autoconfigure.McpServerAutoConfiguration",
          "org.springframework.ai.mcp.server.autoconfigure."
              + "McpServerStreamableHttpWebFluxAutoConfiguration");

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    final String controlPlane = environment.getProperty("yukta.workers.control-plane", "");
    if (!controlPlane.isBlank()) {
      final Map<String, Object> role = new ConcurrentHashMap<>();
      if (!environment.containsProperty("spring.autoconfigure.exclude")) {
        role.put("spring.autoconfigure.exclude", EXCLUDED);
      }
      if (!environment.containsProperty("spring.main.web-application-type")) {
        final boolean management = environment.containsProperty("management.server.port");
        role.put("spring.main.web-application-type", management ? "reactive" : "none");
      }
      environment.getPropertySources().addLast(new MapPropertySource(SOURCE, role));
    }
  }
}
