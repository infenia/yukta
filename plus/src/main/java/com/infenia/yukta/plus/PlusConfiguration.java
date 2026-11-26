// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus;

import com.infenia.yukta.edition.Edition;
import com.infenia.yukta.edition.EditionInfo;
import com.infenia.yukta.plus.ext.EnterpriseModule;
import com.infenia.yukta.plus.ext.EnterpriseModuleRegistry;
import com.infenia.yukta.plus.ext.LicenseVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

/**
 * The Plus edition (AGPL-3.0-only): durable storage in PostgreSQL, high availability and remote
 * workers, each from its own module. This registers the Enterprise modules and tells users where
 * the source is, as section 13 of the AGPL asks.
 */
@NoArgsConstructor
@Configuration(proxyBeanMethods = false)
public class PlusConfiguration {

  /** Present when the PostgreSQL module is in the build. */
  private static final String POSTGRES =
      "com.infenia.yukta.persistence.postgres.PostgresPersistenceAutoConfiguration";

  /** Present when the workers module is in the build. */
  private static final String WORKERS = "com.infenia.workers.WorkersAutoConfiguration";

  /**
   * The installed Enterprise modules, checked against this API version and the licence.
   *
   * @param modules the module beans
   * @param verifier the licence check, if the build has one
   * @return the registry
   */
  @Bean
  public EnterpriseModuleRegistry enterpriseModuleRegistry(
      final ObjectProvider<EnterpriseModule> modules,
      final ObjectProvider<LicenseVerifier> verifier) {
    return new EnterpriseModuleRegistry(
        modules.orderedStream().toList(), verifier.getIfAvailable(LicenseVerifier::none));
  }

  /**
   * What this build is: Plus, or Enterprise when a module is installed.
   *
   * @param registry the installed modules
   * @param source where the corresponding source is offered
   * @return the edition info
   */
  @Bean
  public EditionInfo editionInfo(
      final EnterpriseModuleRegistry registry,
      @Value("${yukta.plus.source-url:https://github.com/infenia/yukta}") final String source) {
    final Edition edition = registry.installed().isEmpty() ? Edition.PLUS : Edition.ENTERPRISE;
    return new EditionInfo(
        edition, edition.getLicense(), features(PlusConfiguration::present, registry), source);
  }

  /**
   * Whether a class is on the classpath.
   *
   * @param name the class name
   * @return true if it can be loaded
   */
  /* default */ static boolean present(final String name) {
    return ClassUtils.isPresent(name, null);
  }

  /**
   * The features of this build: PostgreSQL storage with leader election and remote workers when
   * their modules are present, and what Enterprise modules add.
   *
   * @param present tells whether a class is on the classpath
   * @param registry the installed modules
   * @return feature names, without duplicates
   */
  /* default */ static List<String> features(
      final Predicate<String> present, final EnterpriseModuleRegistry registry) {
    final List<String> features = new ArrayList<>();
    if (present.test(POSTGRES)) {
      features.add("postgres");
      features.add("high-availability");
    }
    if (present.test(WORKERS)) {
      features.add("remote-workers");
    }
    features.addAll(registry.features());
    return features.stream().distinct().toList();
  }

  /**
   * Shows the edition, its licence and where its source is (section 13 of the AGPL).
   *
   * @param edition what this build is
   * @return the contributor for {@code /actuator/info}
   */
  @Bean
  @ConditionalOnProperty(name = "yukta.plus.info", havingValue = "true", matchIfMissing = true)
  public InfoContributor plusInfo(final EditionInfo edition) {
    return builder -> builder.withDetail("edition", edition);
  }
}
