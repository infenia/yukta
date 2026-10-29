// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.edition.Edition;
import com.infenia.yukta.edition.EditionInfo;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.plugin.store.SessionHistoryStore;
import com.infenia.yukta.plus.ext.EnterpriseModule;
import com.infenia.yukta.plus.ext.EnterpriseModuleRegistry;
import com.infenia.yukta.plus.ext.LicenseVerifier;
import com.infenia.yukta.plus.ext.PlusApi;
import com.infenia.yukta.service.store.FileNodeCheckpointStore;
import java.nio.file.Path;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class PlusConfigurationTest {

  private static final String FILE_STORES = "file-stores";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PlusConfiguration.class)
          .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

  @Test
  void withoutDirectoriesOrModules_itIsPlusAndTheInfoShowsTheSource() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(NodeCheckpointStore.class);
          assertThat(context).doesNotHaveBean(SessionHistoryStore.class);
          final EditionInfo edition = context.getBean(EditionInfo.class);
          assertThat(edition.edition()).isEqualTo(Edition.PLUS);
          assertThat(edition.features()).contains(FILE_STORES);
          final Info.Builder info = new Info.Builder();
          context.getBean(InfoContributor.class).contribute(info);
          assertThat(info.build().getDetails()).containsEntry("edition", edition);
        });
  }

  @Test
  void anEnterpriseModule_makesTheBuildEnterpriseAndAddsItsFeatures() {
    runner
        .withBean(
            EnterpriseModule.class,
            () ->
                new EnterpriseModule() {
                  @Override
                  public String moduleId() {
                    return "sso";
                  }

                  @Override
                  public String version() {
                    return "1";
                  }

                  @Override
                  public int requiresPlusApi() {
                    return PlusApi.VERSION;
                  }

                  @Override
                  public List<String> features() {
                    return List.of("sso");
                  }
                })
        .run(
            context -> {
              final EditionInfo edition = context.getBean(EditionInfo.class);
              assertThat(edition.edition()).isEqualTo(Edition.ENTERPRISE);
              assertThat(edition.features()).contains("sso", FILE_STORES);
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

  @Test
  void features_listTheModulesThatArePresent() {
    final EnterpriseModuleRegistry none =
        new EnterpriseModuleRegistry(List.of(), LicenseVerifier.none());

    assertThat(PlusConfiguration.features(name -> false, none)).containsExactly(FILE_STORES);
    assertThat(PlusConfiguration.features(name -> true, none))
        .containsExactly(FILE_STORES, "postgres", "high-availability", "remote-workers");
  }
}
