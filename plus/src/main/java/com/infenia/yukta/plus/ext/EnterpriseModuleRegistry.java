// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.ext;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * The {@link EnterpriseModule}s of this server. Created at start-up: refuses a module built for
 * another {@link PlusApi#VERSION}, asks the {@link LicenseVerifier} and warns when it says the
 * licence does not cover the modules.
 */
@Slf4j
public final class EnterpriseModuleRegistry {

  /** The modules, in the order they were registered. */
  private final List<EnterpriseModule> modules;

  /**
   * Check and keep the modules.
   *
   * @param modules the module beans
   * @param verifier checks the licence
   * @throws IllegalStateException if a module needs another API version
   */
  public EnterpriseModuleRegistry(
      final List<EnterpriseModule> modules, final LicenseVerifier verifier) {
    this.modules = List.copyOf(modules);
    for (final EnterpriseModule module : this.modules) {
      if (module.requiresPlusApi() != PlusApi.VERSION) {
        throw new IllegalStateException(
            "Enterprise module '"
                + module.moduleId()
                + "' "
                + module.version()
                + " was built for Plus API "
                + module.requiresPlusApi()
                + " but this server has API "
                + PlusApi.VERSION
                + ". Use a matching version.");
      }
    }
    if (!this.modules.isEmpty()) {
      final LicenseVerifier.Status status = verifier.verify(this.modules);
      if (!status.valid()) {
        log.atWarn()
            .log(
                "Enterprise modules are installed but the licence check says: {}",
                status.message());
      }
    }
  }

  /**
   * The installed modules.
   *
   * @return the modules
   */
  public List<EnterpriseModule> installed() {
    return modules;
  }

  /**
   * The features of all modules.
   *
   * @return feature names, without duplicates
   */
  public List<String> features() {
    return modules.stream().flatMap(m -> m.features().stream()).distinct().toList();
  }
}
