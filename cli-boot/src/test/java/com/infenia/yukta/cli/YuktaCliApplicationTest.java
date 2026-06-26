/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Hooks;

@SpringBootTest(classes = {YuktaCliApplication.class, CliApplicationConfiguration.class})
class YuktaCliApplicationTest {

  @Test
  void testApplicationContextLoads() {
    // Context should load without errors
  }

  @Test
  @SuppressWarnings("PMD.UnusedLocalVariable")
  void testMainMethod() {
    try (MockedStatic<Hooks> hooksStaticMock = mockStatic(Hooks.class);
        MockedStatic<SpringApplication> ignored = mockStatic(SpringApplication.class)) {
      YuktaCliApplication.main(new String[] {});

      hooksStaticMock.verify(Hooks::enableAutomaticContextPropagation);
    }
  }

  @Test
  @SuppressWarnings("PMD.UnusedLocalVariable")
  void testInitializeAndRunWithoutProfileArgument() {
    try (MockedStatic<Hooks> hooksStaticMock = mockStatic(Hooks.class);
        MockedStatic<SpringApplication> ignored = mockStatic(SpringApplication.class)) {
      YuktaCliApplication.initializeAndRun(new String[] {"--help"});

      hooksStaticMock.verify(Hooks::enableAutomaticContextPropagation);
    }
  }

  @Test
  @SuppressWarnings("PMD.UnusedLocalVariable")
  void testInitializeAndRunWithProfileArgument() {
    try (MockedStatic<Hooks> hooksStaticMock = mockStatic(Hooks.class);
        MockedStatic<SpringApplication> ignored = mockStatic(SpringApplication.class)) {
      YuktaCliApplication.initializeAndRun(new String[] {"--spring.profiles.active=dev"});

      hooksStaticMock.verify(Hooks::enableAutomaticContextPropagation);
    }
  }

  @Test
  void testHasProfileArgumentWithSpringProfilesActiveFormat() throws Exception {
    Method hasProfileArgumentMethod =
        YuktaCliApplication.class.getDeclaredMethod("hasProfileArgument", String[].class);
    hasProfileArgumentMethod.setAccessible(true);

    Boolean result =
        (Boolean)
            hasProfileArgumentMethod.invoke(
                null, (Object) new String[] {"--spring.profiles.active=prod"});
    assertThat(result).isTrue();
  }

  @Test
  void testHasProfileArgumentWithDPropertyFormat() throws Exception {
    Method hasProfileArgumentMethod =
        YuktaCliApplication.class.getDeclaredMethod("hasProfileArgument", String[].class);
    hasProfileArgumentMethod.setAccessible(true);

    Boolean result =
        (Boolean)
            hasProfileArgumentMethod.invoke(
                null, (Object) new String[] {"-Dspring.profiles.active=dev"});
    assertThat(result).isTrue();
  }

  @Test
  void testHasProfileArgumentWithoutProfileArgument() throws Exception {
    Method hasProfileArgumentMethod =
        YuktaCliApplication.class.getDeclaredMethod("hasProfileArgument", String[].class);
    hasProfileArgumentMethod.setAccessible(true);

    Boolean result =
        (Boolean) hasProfileArgumentMethod.invoke(null, (Object) new String[] {"--help"});
    assertThat(result).isFalse();
  }

  @Test
  void testHasProfileArgumentWithEmptyArgs() throws Exception {
    Method hasProfileArgumentMethod =
        YuktaCliApplication.class.getDeclaredMethod("hasProfileArgument", String[].class);
    hasProfileArgumentMethod.setAccessible(true);

    Boolean result = (Boolean) hasProfileArgumentMethod.invoke(null, (Object) new String[] {});
    assertThat(result).isFalse();
  }

  @Test
  void testHasProfileArgumentWithMultipleArgs() throws Exception {
    Method hasProfileArgumentMethod =
        YuktaCliApplication.class.getDeclaredMethod("hasProfileArgument", String[].class);
    hasProfileArgumentMethod.setAccessible(true);

    Boolean result =
        (Boolean)
            hasProfileArgumentMethod.invoke(
                null,
                (Object) new String[] {"--help", "--version", "--spring.profiles.active=prod"});
    assertThat(result).isTrue();
  }

  @Test
  void testIsNativeImageWhenNotInNativeImage() throws Exception {
    Method isNativeImageMethod = YuktaCliApplication.class.getDeclaredMethod("isNativeImage");
    isNativeImageMethod.setAccessible(true);

    Boolean result = (Boolean) isNativeImageMethod.invoke(null);
    assertThat(result).isFalse();
  }

  @Test
  @SuppressWarnings("PMD.UnusedLocalVariable")
  void testInitializeAndRunInNativeImageWithoutProfileArgument() {
    String originalProperty = System.getProperty("org.graalvm.nativeimage.imagecode");
    try {
      System.setProperty("org.graalvm.nativeimage.imagecode", "runtime");

      try (MockedStatic<Hooks> hooksStaticMock = mockStatic(Hooks.class);
          MockedStatic<SpringApplication> ignored = mockStatic(SpringApplication.class)) {
        YuktaCliApplication.initializeAndRun(new String[] {});

        hooksStaticMock.verify(Hooks::enableAutomaticContextPropagation);
      }
    } finally {
      if (originalProperty == null) {
        System.clearProperty("org.graalvm.nativeimage.imagecode");
      } else {
        System.setProperty("org.graalvm.nativeimage.imagecode", originalProperty);
      }
    }
  }

  @Test
  @SuppressWarnings("PMD.UnusedLocalVariable")
  void testInitializeAndRunInNativeImageWithProfileArgument() {
    String originalProperty = System.getProperty("org.graalvm.nativeimage.imagecode");
    try {
      System.setProperty("org.graalvm.nativeimage.imagecode", "runtime");

      try (MockedStatic<Hooks> hooksStaticMock = mockStatic(Hooks.class);
          MockedStatic<SpringApplication> ignored = mockStatic(SpringApplication.class)) {
        YuktaCliApplication.initializeAndRun(new String[] {"--spring.profiles.active=custom"});

        hooksStaticMock.verify(Hooks::enableAutomaticContextPropagation);
      }
    } finally {
      if (originalProperty == null) {
        System.clearProperty("org.graalvm.nativeimage.imagecode");
      } else {
        System.setProperty("org.graalvm.nativeimage.imagecode", originalProperty);
      }
    }
  }
}
