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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class SystemExitHandlerTest {

  @Test
  void isFunctionalInterface_canBeImplementedAsLambda() {
    SystemExitHandler handler = code -> {};

    assertThat(handler).isNotNull();
  }

  @Test
  void exit_withSuccessCode_callsImplementation() {
    SystemExitHandler handler = mock(SystemExitHandler.class);
    handler.exit(0);

    verify(handler).exit(0);
  }

  @Test
  void exit_withErrorCode_callsImplementation() {
    SystemExitHandler handler = mock(SystemExitHandler.class);
    handler.exit(1);

    verify(handler).exit(1);
  }

  @Test
  void exit_withNegativeCode_callsImplementation() {
    SystemExitHandler handler = mock(SystemExitHandler.class);
    handler.exit(-1);

    verify(handler).exit(-1);
  }

  @Test
  void exit_withCustomCode_callsImplementation() {
    SystemExitHandler handler = mock(SystemExitHandler.class);
    int customCode = 42;
    handler.exit(customCode);

    verify(handler).exit(customCode);
  }

  @Test
  void implementationUsingLambda_canCaptureExitCode() {
    int[] capturedCode = new int[1];
    SystemExitHandler handler = code -> capturedCode[0] = code;

    handler.exit(5);

    assertThat(capturedCode[0]).isEqualTo(5);
  }

  @Test
  void implementationUsingMethodReference_works() {
    SystemExitHandler handler = System::exit;

    assertThat(handler).isNotNull();
  }

  @Test
  void multipleHandlers_canBeCreated() {
    SystemExitHandler handler1 = code -> {};
    SystemExitHandler handler2 = code -> {};

    assertThat(handler1).isNotEqualTo(handler2);
  }
}
