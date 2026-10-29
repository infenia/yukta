// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.AvoidDuplicateLiterals"})
class PostgresPersistenceAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PostgresPersistenceAutoConfiguration.class))
          .withBean(DatabaseClient.class, () -> mock(DatabaseClient.class))
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(SessionConfigProperties.class, SessionConfigProperties::new);

  @Test
  void withStoreTypePostgres_theThreeStoresAreDatabaseBacked() {
    runner
        .withPropertyValues("yukta.session.store-type=postgres")
        .run(
            context -> {
              assertThat(context.getBean(WorkflowDefinitionStore.class))
                  .isInstanceOf(R2dbcWorkflowDefinitionStore.class);
              assertThat(context.getBean(SessionConfigStore.class))
                  .isInstanceOf(R2dbcSessionConfigStore.class);
              assertThat(context.getBean(ExecutionLogStore.class))
                  .isInstanceOf(R2dbcExecutionLogStore.class);
            });
  }

  @Test
  void withRestartStoreTypePostgres_theCheckpointsAreDatabaseBacked() {
    runner
        .withPropertyValues(
            "yukta.session.store-type=postgres", "yukta.restart.store-type=postgres")
        .run(
            context ->
                assertThat(context.getBean(NodeCheckpointStore.class))
                    .isInstanceOf(R2dbcNodeCheckpointStore.class));
    runner
        .withPropertyValues("yukta.session.store-type=postgres")
        .run(context -> assertThat(context).doesNotHaveBean(NodeCheckpointStore.class));
  }

  @Test
  void withKvStoreTypePostgres_theKeyValueStoreIsDatabaseBacked() {
    runner
        .withPropertyValues("yukta.session.store-type=postgres", "yukta.kv.store-type=postgres")
        .run(
            context ->
                assertThat(context.getBean(com.infenia.yukta.plugin.store.KeyValueStore.class))
                    .isInstanceOf(R2dbcKeyValueStore.class));
    runner
        .withPropertyValues("yukta.session.store-type=postgres")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(com.infenia.yukta.plugin.store.KeyValueStore.class));
  }

  @Test
  void withSessionMemoryStoreTypePostgres_theHistoryIsDatabaseBacked() {
    runner
        .withInitializer(
            context ->
                context
                    .getBeanFactory()
                    .setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService
                            .getSharedInstance()))
        .withPropertyValues(
            "yukta.session.store-type=postgres", "yukta.session-memory.store-type=postgres")
        .run(
            context ->
                assertThat(
                        context.getBean(com.infenia.yukta.plugin.store.SessionHistoryStore.class))
                    .isInstanceOf(R2dbcSessionHistoryStore.class));
  }

  @Test
  void withHaModePostgres_theLeaderElectionIsDatabaseBacked() {
    runner
        .withInitializer(
            context ->
                context
                    .getBeanFactory()
                    .setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService
                            .getSharedInstance()))
        .withBean(
            io.micrometer.core.instrument.MeterRegistry.class,
            io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
        .withPropertyValues("yukta.session.store-type=postgres", "yukta.ha.mode=postgres")
        .run(
            context ->
                assertThat(context.getBean(com.infenia.yukta.service.leader.LeaderElection.class))
                    .isInstanceOf(R2dbcLeaderElection.class));
    runner
        .withPropertyValues("yukta.session.store-type=postgres")
        .run(context -> assertThat(context).doesNotHaveBean(R2dbcLeaderElection.class));
  }

  @Test
  void withAnyOtherStoreType_nothingIsRegistered() {
    runner
        .withPropertyValues("yukta.session.store-type=file")
        .run(context -> assertThat(context).doesNotHaveBean(WorkflowDefinitionStore.class));
    runner.run(context -> assertThat(context).doesNotHaveBean(ExecutionLogStore.class));
  }
}
