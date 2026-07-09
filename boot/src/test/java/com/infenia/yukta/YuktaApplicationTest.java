// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
  @Timeout(20)
  void mainStartsApplicationWithoutError() {
    assertThatNoException()
        .isThrownBy(
            () -> {
              final ExecutorService executor = Executors.newSingleThreadExecutor();
              @SuppressWarnings("unused")
              Future<?> startTask =
                  executor.submit(() -> YuktaApplication.main(new String[] {"--server.port=0"}));
              executor.shutdown();
              final boolean completed = executor.awaitTermination(10, TimeUnit.SECONDS);
              if (!completed) {
                executor.shutdownNow();
              }
            });
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
