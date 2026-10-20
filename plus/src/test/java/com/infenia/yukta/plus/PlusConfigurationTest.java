// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.plugin.store.SessionHistoryStore;
import com.infenia.yukta.service.store.FileNodeCheckpointStore;
import java.nio.file.Path;
import lombok.NoArgsConstructor;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.UnitTestShouldIncludeAssert"})
class PlusConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PlusConfiguration.class)
          .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

  @Test
  void withoutDirectories_onlyTheInfoContributorIsRegistered() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(NodeCheckpointStore.class);
          assertThat(context).doesNotHaveBean(SessionHistoryStore.class);
          final Info.Builder info = new Info.Builder();
          context.getBean(InfoContributor.class).contribute(info);
          assertThat(info.build().getDetails())
              .extractingByKey("edition")
              .asInstanceOf(InstanceOfAssertFactories.MAP)
              .containsEntry("license", "AGPL-3.0-only")
              .containsKey("source");
        });
  }

  @Test
  void withDirectories_fileStoresAreRegistered(@TempDir final Path dir) {
    runner
        .withPropertyValues(
            "yukta.restart.dir=" + dir.resolve("r"), "yukta.session-memory.dir=" + dir.resolve("m"))
        .run(
            context -> {
              assertThat(context.getBean(NodeCheckpointStore.class))
                  .isInstanceOf(FileNodeCheckpointStore.class);
              assertThat(context.getBean(SessionHistoryStore.class))
                  .isInstanceOf(FileSessionHistoryStore.class);
            });
  }

  @Test
  void withPostgres_noFileStoreIsRegistered(@TempDir final Path dir) {
    runner
        .withPropertyValues(
            "yukta.restart.dir=" + dir,
            "yukta.restart.store-type=postgres",
            "yukta.session-memory.dir=" + dir,
            "yukta.session-memory.store-type=postgres")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(NodeCheckpointStore.class);
              assertThat(context).doesNotHaveBean(SessionHistoryStore.class);
            });
  }
}
