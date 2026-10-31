// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class DaemonTest {

  private static final String PID = "yukta.pid";
  private static final String LOG = "yukta.log";

  @TempDir /* default */ Path home;

  private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

  private Daemon daemon() {
    // port 1 refuses connections at once, so drain and health fail fast
    return new Daemon(home, Map.of("SERVER_PORT", "1"), new PrintStream(buf, true, UTF_8));
  }

  @Test
  void homeFollowsYuktaHomeThenXdgThenUserHome() {
    assertThat(Daemon.homeFrom(Map.of("YUKTA_HOME", "a"))).isEqualTo(Path.of("a"));
    assertThat(Daemon.homeFrom(Map.of("XDG_STATE_HOME", "s"))).isEqualTo(Path.of("s/yukta"));
    assertThat(Daemon.homeFrom(Map.of()).toString()).endsWith(".local/state/yukta");
  }

  @Test
  void handlesOnlyTheLifecycleCommands() {
    assertThat(Daemon.handles("status")).isTrue();
    assertThat(Daemon.handles("--version")).isFalse();
    assertThat(Daemon.handles()).isFalse();
  }

  @Test
  void statusWithoutPidFileIsStopped() throws Exception {
    assertThat(daemon().run("status")).isEqualTo(3);
    assertThat(buf.toString(UTF_8)).contains("not running");
  }

  @Test
  void stalePidFileCountsAsStopped() throws Exception {
    Files.writeString(home.resolve(PID), "999999999");
    assertThat(daemon().run("status")).isEqualTo(3);
    assertThat(daemon().run("stop")).isZero();
    assertThat(home.resolve(PID)).doesNotExist();
  }

  @Test
  void stopTerminatesTheRecordedProcess() throws Exception {
    final Process p = new ProcessBuilder("sleep", "60").start();
    final long started = p.toHandle().info().startInstant().orElseThrow().toEpochMilli();
    Files.writeString(home.resolve(PID), p.pid() + " " + started);
    assertThat(daemon().run("status")).isZero();
    assertThat(daemon().run("stop")).isZero();
    assertThat(p.isAlive()).isFalse();
    assertThat(home.resolve(PID)).doesNotExist();
  }

  @Test
  void logsPrintsTheTail() throws IOException, InterruptedException {
    Files.writeString(home.resolve(LOG), "one\ntwo\n");
    assertThat(daemon().run("logs")).isZero();
    assertThat(buf.toString(UTF_8)).contains("one", "two");
  }
}
