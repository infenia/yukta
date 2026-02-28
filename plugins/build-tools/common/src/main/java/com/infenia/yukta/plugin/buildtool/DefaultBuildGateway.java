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
package com.infenia.yukta.plugin.buildtool;

import com.infenia.yukta.plugin.BuildGateway;
import com.infenia.yukta.plugin.Message;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.StringDecoder;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Default implementation of the {@link BuildGateway} that executes external build tasks. */
@Slf4j
@Service
@SuppressWarnings("PMD.OnlyOneReturn")
public class DefaultBuildGateway implements BuildGateway {

  /** Default constructor. */
  public DefaultBuildGateway() {
    super();
  }

  @Override
  public Mono<String> executeTask(
      final File projectDir,
      final List<String> command,
      final String taskName,
      final long timeout) {

    return Mono.fromCallable(
            () -> {
              final ProcessBuilder processBuilder = new ProcessBuilder(command);
              processBuilder.directory(projectDir);
              processBuilder.redirectErrorStream(true);
              return processBuilder.start();
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(
            process -> {
              final Flux<String> outputFlux =
                  DataBufferUtils.readInputStream(
                          process::getInputStream, DefaultDataBufferFactory.sharedInstance, 4096)
                      .transform(
                          flux -> StringDecoder.textPlainOnly().decode(flux, null, null, Map.of()));

              final Mono<Integer> exitCodeMono =
                  Mono.fromFuture(process.onExit()).map(Process::exitValue);

              return outputFlux
                  .collectList()
                  .map(list -> String.join("", list))
                  .zipWith(exitCodeMono)
                  .map(
                      tuple -> {
                        final String logs = tuple.getT1();
                        final Integer code = tuple.getT2();
                        if (code != 0 && log.isWarnEnabled()) {
                          log.warn("Task {} failed with exit code {}", taskName, code);
                        }
                        return logs;
                      })
                  .timeout(Duration.ofSeconds(timeout))
                  .onErrorResume(
                      TimeoutException.class,
                      e -> {
                        process.destroyForcibly();
                        return Mono.error(
                            new TimeoutException("Timeout running task: " + taskName));
                      });
            })
        .onErrorResume(
            IOException.class,
            e ->
                Mono.error(
                    new RuntimeException(
                        "Error executing task " + taskName + ": " + e.getMessage())));
  }

  @Override
  public <T, R> Mono<Message<R>> sendAndReceive(final Message<T> request) {
    // Basic implementation that executes a build if command is in headers
    final List<String> command = (List<String>) request.getMetadata().get("command");
    if (command == null) {
      return Mono.error(
          new IllegalArgumentException("command is required for gateway sendAndReceive"));
    }

    final File projectDir = new File(".");
    final long timeout = 600L;

    return executeTask(projectDir, command, "gateway-task", timeout)
        .map(logs -> (Message<R>) request.withPayload(logs));
  }

  @Override
  public <T> Mono<Void> send(final Message<T> request) {
    return sendAndReceive(request).then();
  }

  @Override
  public <R> Flux<Message<R>> receive() {
    return Flux.empty();
  }
}
