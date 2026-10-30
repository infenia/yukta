// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.ext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class EnterpriseModuleRegistryTest {

  private static EnterpriseModule module(final int api, final String... features) {
    return new EnterpriseModule() {
      @Override
      public String moduleId() {
        return "m" + api;
      }

      @Override
      public String version() {
        return "1.0";
      }

      @Override
      public int requiresPlusApi() {
        return api;
      }

      @Override
      public List<String> features() {
        return List.of(features);
      }
    };
  }

  @Test
  void noModules_meansNoFeaturesAndNoLicenceCheck() {
    final EnterpriseModuleRegistry registry =
        new EnterpriseModuleRegistry(
            List.of(),
            modules -> {
              throw new AssertionError("not asked");
            });

    assertThat(registry.installed()).isEmpty();
    assertThat(registry.features()).isEmpty();
  }

  @Test
  void moduleWithoutFeatures_addsNone() {
    final EnterpriseModule plain =
        new EnterpriseModule() {
          @Override
          public String moduleId() {
            return "plain";
          }

          @Override
          public String version() {
            return "1";
          }

          @Override
          public int requiresPlusApi() {
            return PlusApi.VERSION;
          }
        };

    assertThat(new EnterpriseModuleRegistry(List.of(plain), LicenseVerifier.none()).features())
        .isEmpty();
  }

  @Test
  void compatibleModules_areKept_andTheirFeaturesListedOnce() {
    final EnterpriseModuleRegistry registry =
        new EnterpriseModuleRegistry(
            List.of(module(PlusApi.VERSION, "sso", "audit"), module(PlusApi.VERSION, "audit")),
            LicenseVerifier.none());

    assertThat(registry.installed()).hasSize(2);
    assertThat(registry.features()).containsExactly("sso", "audit");
  }

  @Test
  void moduleBuiltForAnotherApiVersion_isRefused() {
    assertThatThrownBy(
            () ->
                new EnterpriseModuleRegistry(
                    List.of(module(PlusApi.VERSION + 1)), LicenseVerifier.none()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("m2")
        .hasMessageContaining("Plus API");
  }

  @Test
  void verifierThatAccepts_isAskedOnce() {
    final int[] asked = {0};
    new EnterpriseModuleRegistry(
        List.of(module(PlusApi.VERSION)),
        modules -> {
          asked[0]++;
          return new LicenseVerifier.Status(true, "ok");
        });

    assertThat(asked[0]).isEqualTo(1);
  }
}
