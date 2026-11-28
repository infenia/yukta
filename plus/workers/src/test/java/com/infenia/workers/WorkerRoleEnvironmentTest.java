// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class WorkerRoleEnvironmentTest {

  private static final String CONTROL_PLANE = "yukta.workers.control-plane";
  private static final String EXCLUDE = "spring.autoconfigure.exclude";
  private static final String WEB_TYPE = "spring.main.web-application-type";
  private static final String PLANE_URL = "grpc://plane:9000";

  private static StandardEnvironment environment(final Map<String, Object> properties) {
    final StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
    return environment;
  }

  private static void apply(final StandardEnvironment environment) {
    new WorkerRoleEnvironment().postProcessEnvironment(environment, null);
  }

  @Test
  void leavesEnvironmentAloneWhenNoControlPlaneIsSet() {
    final StandardEnvironment environment = environment(Map.of());

    apply(environment);

    assertThat(environment.getPropertySources().contains(WorkerRoleEnvironment.SOURCE)).isFalse();
    assertThat(environment.containsProperty(EXCLUDE)).isFalse();
    assertThat(environment.containsProperty(WEB_TYPE)).isFalse();
  }

  @Test
  void leavesEnvironmentAloneWhenControlPlaneIsBlank() {
    final StandardEnvironment environment = environment(Map.of(CONTROL_PLANE, "  "));

    apply(environment);

    assertThat(environment.getPropertySources().contains(WorkerRoleEnvironment.SOURCE)).isFalse();
    assertThat(environment.containsProperty(WEB_TYPE)).isFalse();
  }

  @Test
  void excludesDatabaseAndMcpAndHasNoWebServerWhenNoManagementPort() {
    final StandardEnvironment environment = environment(Map.of(CONTROL_PLANE, PLANE_URL));

    apply(environment);

    assertThat(environment.getProperty(WEB_TYPE)).isEqualTo("none");
    final String excluded = environment.getProperty(EXCLUDE);
    assertThat(excluded)
        .contains("org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration")
        .contains("org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
        .contains("com.infenia.yukta.persistence.postgres.PostgresPersistenceAutoConfiguration")
        .contains("org.springframework.ai.mcp.server.autoconfigure.McpServerAutoConfiguration");
  }

  @Test
  void servesHealthAndMetricsWhenManagementPortIsSet() {
    final StandardEnvironment environment =
        environment(Map.of(CONTROL_PLANE, PLANE_URL, "management.server.port", 8081));

    apply(environment);

    assertThat(environment.getProperty(WEB_TYPE)).isEqualTo("reactive");
  }

  @Test
  void keepsOperatorExcludeListWhenSet() {
    final StandardEnvironment environment =
        environment(Map.of(CONTROL_PLANE, PLANE_URL, EXCLUDE, "com.example.Custom"));

    apply(environment);

    assertThat(environment.getProperty(EXCLUDE)).isEqualTo("com.example.Custom");
    assertThat(environment.getProperty(WEB_TYPE)).isEqualTo("none");
  }

  @Test
  void keepsOperatorWebApplicationTypeWhenSet() {
    final StandardEnvironment environment =
        environment(Map.of(CONTROL_PLANE, PLANE_URL, WEB_TYPE, "servlet"));

    apply(environment);

    assertThat(environment.getProperty(WEB_TYPE)).isEqualTo("servlet");
  }
}
