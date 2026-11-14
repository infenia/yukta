// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The writer of a process' standard input. */
@NoArgsConstructor
class LockedStdinTest {

  /** A stream whose process has gone: every call fails. */
  private static final class ClosedStream extends OutputStream {
    @Override
    public void write(final int b) throws IOException {
      throw new IOException("closed");
    }

    @Override
    public void close() throws IOException {
      throw new IOException("closed");
    }
  }

  @Test
  void writesOneLineAndFlushes() {
    final ByteArrayOutputStream sink = new ByteArrayOutputStream();

    new LockedStdin(sink).write("hello");

    assertThat(sink.toString(StandardCharsets.UTF_8)).isEqualTo("hello\n");
  }

  @Test
  void processThatHasClosedStream_makesWriteAndCloseNoOps() {
    final LockedStdin stdin = new LockedStdin(new ClosedStream());

    assertThatCode(
            () -> {
              stdin.write("late");
              stdin.close();
            })
        .doesNotThrowAnyException();
  }

  @Test
  void closeClosesTheStream() {
    final boolean[] closed = {false};
    final OutputStream stream =
        new OutputStream() {
          @Override
          public void write(final int b) {
            // not used
          }

          @Override
          public void close() {
            closed[0] = true;
          }
        };

    new LockedStdin(stream).close();

    assertThat(closed[0]).isTrue();
  }
}
