// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/** A process' standard input that several threads may write to; a failed write is only logged. */
@Slf4j
final class LockedStdin implements ProcessExecutorGateway.StdinWriter {

  private final OutputStream stream;
  private final ReentrantLock lock = new ReentrantLock();

  /* default */ LockedStdin(final OutputStream stream) {
    this.stream = stream;
  }

  @Override
  public void write(final String line) {
    lock.lock();
    try {
      stream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
      stream.flush();
    } catch (final IOException e) {
      log.atDebug()
          .setMessage("Stdin write failed (the process may have exited)")
          .setCause(e)
          .log();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    lock.lock();
    try {
      stream.close();
    } catch (final IOException e) {
      log.atDebug().setMessage("Stdin close failed").setCause(e).log();
    } finally {
      lock.unlock();
    }
  }
}
