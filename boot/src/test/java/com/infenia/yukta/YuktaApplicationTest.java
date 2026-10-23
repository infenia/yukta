// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.*;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@SuppressWarnings({
  "PMD.AvoidThrowingRawExceptionTypes",
  "PMD.AvoidCatchingGenericException",
  "PMD.AvoidAccessibilityAlteration",
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "SpotBugs.UwF_UNWRITTEN_FIELD"
})
@NoArgsConstructor
class YuktaApplicationTest {

  private static final String PORT_ARG = "--server.port=0";

  private static final String PROFILE_PROPERTY = "spring.profiles.active";

  private static final String NATIVE_IMAGE_PROPERTY = "org.graalvm.nativeimage.imagecode";

  private String originalNativeImageProperty;

  @BeforeEach
  void setUp() {
    originalNativeImageProperty = System.getProperty(NATIVE_IMAGE_PROPERTY);
  }

  @AfterEach
  void tearDown() {
    if (originalNativeImageProperty != null) {
      System.setProperty(NATIVE_IMAGE_PROPERTY, originalNativeImageProperty);
    } else {
      System.clearProperty(NATIVE_IMAGE_PROPERTY);
    }
  }

  @Test
  @Timeout(60)
  void mainStartsApplicationWithoutError() {
    // the context stays up until the JVM's shutdown hook closes it
    assertThatNoException().isThrownBy(() -> YuktaApplication.main(new String[] {PORT_ARG}));
  }

  @Test
  @Timeout(60)
  void mainSelectsProdProfileInNativeImageWithoutOne() {
    final String before = System.getProperty(PROFILE_PROPERTY);
    // Spring caches its native-image check; let it settle before the property is faked
    org.springframework.core.NativeDetector.inNativeImage();
    System.setProperty(NATIVE_IMAGE_PROPERTY, "runtime");
    System.clearProperty(PROFILE_PROPERTY);
    try {
      try {
        YuktaApplication.main(new String[] {PORT_ARG});
      } catch (RuntimeException e) {
        // the prod profile may not start in a test; only the profile selection matters here
        assertThat(e).isNotNull();
      }
      assertThat(System.getProperty(PROFILE_PROPERTY)).isEqualTo("prod");

      // an explicit profile argument is left alone
      System.clearProperty(PROFILE_PROPERTY);
      YuktaApplication.main(new String[] {PORT_ARG, "--spring.profiles.active=dev"});
      assertThat(System.getProperty(PROFILE_PROPERTY)).isNull();
    } finally {
      if (before == null) {
        System.clearProperty(PROFILE_PROPERTY);
      } else {
        System.setProperty(PROFILE_PROPERTY, before);
      }
    }
  }

  @Test
  void infoOutputHandlesVersionAndHelpOnly() {
    assertThat(YuktaApplication.infoOutput("--version")).startsWith("yukta version ");
    assertThat(YuktaApplication.infoOutput(PORT_ARG, "--help")).contains("Usage: yukta");
    assertThat(YuktaApplication.infoOutput("-h")).contains("YUKTA_API_KEY");
    assertThat(YuktaApplication.infoOutput(PORT_ARG)).isNull();
  }

  @Test
  @SuppressWarnings("PMD.CloseResource") // System.out is restored, never closed
  void mainPrintsTheVersionAndHelpWithoutStartingTheServer() {
    final java.io.PrintStream original = System.out;
    final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      YuktaApplication.main(new String[] {"--version"});
      YuktaApplication.main(new String[] {"--help"});
    } finally {
      System.setOut(original);
    }
    assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8))
        .contains("yukta version ")
        .contains("Usage: yukta");
  }

  @Test
  @SuppressWarnings("PMD.CloseResource") // versionFrom closes the stream it is given
  void versionFromReadsTheBuildVersionAndFallsBackToUnknown() {
    final java.io.InputStream broken =
        new java.io.InputStream() {
          @Override
          public int read() throws java.io.IOException {
            throw new java.io.IOException("unreadable");
          }
        };
    assertThat(
            YuktaApplication.versionFrom(
                new java.io.ByteArrayInputStream(
                    "build.version=1.2.3".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        .isEqualTo("1.2.3");
    assertThat(YuktaApplication.versionFrom(null)).isEqualTo("unknown");
    assertThat(YuktaApplication.versionFrom(broken)).isEqualTo("unknown");
  }

  @Test
  void testIsNativeImageWhenPropertyNotSet() {
    System.clearProperty(NATIVE_IMAGE_PROPERTY);
    assertThat(isNativeImageReflection()).isFalse();
  }

  @Test
  void testIsNativeImageWhenRuntimeProperty() {
    System.setProperty(NATIVE_IMAGE_PROPERTY, "runtime");
    assertThat(isNativeImageReflection()).isTrue();
  }

  @Test
  void testIsNativeImageWhenBuildtimeProperty() {
    System.setProperty(NATIVE_IMAGE_PROPERTY, "buildtime");
    assertThat(isNativeImageReflection()).isFalse();
  }

  @Test
  void testIsNativeImageWhenOtherProperty() {
    System.setProperty(NATIVE_IMAGE_PROPERTY, "other");
    assertThat(isNativeImageReflection()).isFalse();
  }

  @Test
  void profileEnvironmentVariableCountsAsChosen() {
    assertThat(
            YuktaApplication.profileChosen(
                java.util.Map.of("SPRING_PROFILES_ACTIVE", "prod,postgres")))
        .isTrue();
    assertThat(YuktaApplication.profileChosen(java.util.Map.of())).isFalse();
  }

  @Test
  void testHasProfileArgumentWithActiveProfileFlag() {
    final String[] args = {"--spring.profiles.active=prod"};
    assertThat(hasProfileArgumentReflection(args)).isTrue();
  }

  @SuppressWarnings("checkstyle:abbreviationaswordinname")
  @Test
  void testHasProfileArgumentWithDFlag() {
    final String[] args = {"-Dspring.profiles.active=dev"};
    assertThat(hasProfileArgumentReflection(args)).isTrue();
  }

  @Test
  void testHasProfileArgumentWithMultipleArgs() {
    final String[] args = {"--server.port=8080", "--spring.profiles.active=test"};
    assertThat(hasProfileArgumentReflection(args)).isTrue();
  }

  @Test
  void testHasProfileArgumentWithActiveProfileFlagAndValue() {
    final String[] args = {"--spring.profiles.active=production"};
    assertThat(hasProfileArgumentReflection(args)).isTrue();
  }

  @SuppressWarnings("checkstyle:abbreviationaswordinname")
  @Test
  void testHasProfileArgumentWithDFlagAndValue() {
    final String[] args = {"-Dspring.profiles.active=development"};
    assertThat(hasProfileArgumentReflection(args)).isTrue();
  }

  @Test
  void testHasProfileArgumentWithNoProfile() {
    final String[] args = {"--server.port=8080", "--logging.level=debug"};
    assertThat(hasProfileArgumentReflection(args)).isFalse();
  }

  @Test
  void testHasProfileArgumentWithEmptyArgs() {
    final String[] args = {};
    assertThat(hasProfileArgumentReflection(args)).isFalse();
  }

  @Test
  void testHasProfileArgumentWithSimilarFlag() {
    final String[] args = {"--spring.profiles.include=extra"};
    assertThat(hasProfileArgumentReflection(args)).isFalse();
  }

  @Test
  void testHasProfileArgumentLoopsAllArgs() {
    final String[] args = {
      "--server.port=8080", "--logging.level=debug", "--spring.profiles.active=final"
    };
    assertThat(hasProfileArgumentReflection(args)).isTrue();
  }

  @Test
  void testHasProfileArgumentBreaksOnFirstMatch() {
    final String[] args = {"--spring.profiles.active=first", "--spring.profiles.active=second"};
    assertThat(hasProfileArgumentReflection(args)).isTrue();
  }

  private boolean isNativeImageReflection() {
    try {
      final var method = YuktaApplication.class.getDeclaredMethod("isNativeImage");
      method.setAccessible(true);
      return (boolean) method.invoke(null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private boolean hasProfileArgumentReflection(final String... args) {
    try {
      final var method =
          YuktaApplication.class.getDeclaredMethod("hasProfileArgument", String[].class);
      method.setAccessible(true);
      return (boolean) method.invoke(null, (Object) args);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
