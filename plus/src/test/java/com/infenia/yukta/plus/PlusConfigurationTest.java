// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.edition.Edition;
import com.infenia.yukta.edition.EditionInfo;
import com.infenia.yukta.plus.ext.EnterpriseModule;
import com.infenia.yukta.plus.ext.EnterpriseModuleRegistry;
import com.infenia.yukta.plus.ext.LicenseVerifier;
import com.infenia.yukta.plus.ext.PlusApi;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class PlusConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PlusConfiguration.class)
          .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

  @Test
  void withoutModules_itIsPlusAndTheInfoShowsTheSource() {
    runner.run(context -> {
      final EditionInfo edition = context.getBean(EditionInfo.class);
      assertThat(edition.edition()).isEqualTo(Edition.PLUS);
      assertThat(edition.features()).isEmpty();
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
        .run(context -> {
          final EditionInfo edition = context.getBean(EditionInfo.class);
          assertThat(edition.edition()).isEqualTo(Edition.ENTERPRISE);
          assertThat(edition.features()).containsExactly("sso");
        });
  }

  @Test
  void features_listTheModulesThatArePresent() {
    final EnterpriseModuleRegistry none =
        new EnterpriseModuleRegistry(List.of(), LicenseVerifier.none());

    assertThat(PlusConfiguration.features(name -> false, none)).isEmpty();
    assertThat(PlusConfiguration.features(name -> true, none))
        .containsExactly("postgres", "high-availability", "remote-workers");
  }
}
