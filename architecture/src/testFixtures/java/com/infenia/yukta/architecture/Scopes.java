// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/** Import options that keep only the classes of one module, for rules that run inside it. */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class Scopes {

  private Scopes() {}

  /** Keeps only classes built in one module directory. */
  private abstract static class ModuleScope implements ImportOption {
    private final String dir;

    /* default */ ModuleScope(final String dir) {
      this.dir = dir;
    }

    @Override
    public boolean includes(final Location location) {
      return location.contains(dir + "/build/");
    }
  }

  /** Drops test-support: a helper for tests that production rules are not about. */
  public static final class WithoutTestSupport implements ImportOption {
    @Override
    public boolean includes(final Location location) {
      return !location.contains("/test-support/build/");
    }
  }

  /** Only the {@code core} module. */
  public static final class OnlyCore extends ModuleScope {
    /** Creates the option. */
    public OnlyCore() {
      super("/core");
    }
  }

  /** Only the {@code web} module. */
  public static final class OnlyWeb extends ModuleScope {
    /** Creates the option. */
    public OnlyWeb() {
      super("/web");
    }
  }

  /** Only the {@code mcp} module. */
  public static final class OnlyMcp extends ModuleScope {
    /** Creates the option. */
    public OnlyMcp() {
      super("/mcp");
    }
  }

  /** Only the {@code plugin-api} module. */
  public static final class OnlyPluginApi extends ModuleScope {
    /** Creates the option. */
    public OnlyPluginApi() {
      super("/plugin-api");
    }
  }
}
