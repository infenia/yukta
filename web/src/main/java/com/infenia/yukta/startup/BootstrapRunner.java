// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.startup;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.mapper.SessionMapper;
import com.infenia.yukta.service.session.SessionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PreDestroy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies every session file ({@code *.json}, the body of {@code POST /api/sessions}) in the folder
 * {@code yukta.bootstrap.dir} when the server starts, in name order. State kept in memory is lost
 * on restart, so this lets definitions live in files (for example in git) and come back by
 * themselves. A file that cannot be read, is invalid or cannot be applied is logged and skipped; it
 * never stops start-up.
 *
 * <p>With {@code yukta.bootstrap.watch=true} the folder is then polled every {@code
 * yukta.bootstrap.watch-interval} (default 5s) and a new or changed file is applied again, so a
 * definition changed in git reaches the running server. A file removed from the folder is kept as a
 * session. Polling, not a file-system watch, so it also works on network mounts and after a {@code
 * git pull}.
 *
 * <p>Nothing here blocks a thread: the files are applied reactively, and until the first pass is
 * done the readiness state is held at {@code REFUSING_TRAFFIC} (Spring Boot opens it when the
 * runners return, which is now earlier), so a readiness probe still waits for the sessions.
 */
@Slf4j
@Component
@SuppressWarnings("PMD.ExcessiveImports")
@ConditionalOnExpression("!'${yukta.bootstrap.dir:}'.isEmpty()")
public class BootstrapRunner implements ApplicationRunner {

  /** Longest one file may take to apply. */
  private static final Duration APPLY_TIMEOUT = Duration.ofSeconds(30);

  /** The folder with the session files. */
  private final Path dir;

  /** Reads the files. */
  private final ObjectMapper mapper;

  /** Checks the files like the REST endpoint does. */
  private final Validator validator;

  /** Maps the request to the session model. */
  private final SessionMapper sessionMapper;

  /** Applies the sessions. */
  private final SessionService sessionService;

  /** Whether to keep watching the folder after start-up. */
  private final boolean watch;

  /** How often the folder is checked. */
  private final Duration interval;

  /** The last modification time seen per file; a file is applied again when it differs. */
  private final Map<Path, FileTime> seen = new ConcurrentHashMap<>();

  /** The running watch, or null. */
  private final ReentrantLock lock = new ReentrantLock();

  private Disposable watcher;

  /** Publishes the readiness state. */
  private final ApplicationEventPublisher events;

  /** True from the start of the first pass until it is done. */
  private boolean pending;

  /** True if the readiness state was held back while the first pass ran. */
  private boolean refused;

  /**
   * Create the runner.
   *
   * @param dir the folder with the session files
   * @param mapper reads the files
   * @param validator checks the files
   * @param sessionMapper maps the request to the session model
   * @param sessionService applies the sessions
   * @param watch whether to keep watching the folder
   * @param interval how often to check the folder
   * @param events publishes the readiness state
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // all are shared Spring beans
  public BootstrapRunner(
      @Value("${yukta.bootstrap.dir}") final String dir,
      final ObjectMapper mapper,
      final Validator validator,
      final SessionMapper sessionMapper,
      final SessionService sessionService,
      @Value("${yukta.bootstrap.watch:false}") final boolean watch,
      @Value("${yukta.bootstrap.watch-interval:5s}") final Duration interval,
      final ApplicationEventPublisher events) {
    this.dir = Path.of(dir);
    this.mapper = mapper;
    this.validator = validator;
    this.sessionMapper = sessionMapper;
    this.sessionService = sessionService;
    this.watch = watch;
    this.interval = interval;
    this.events = events;
  }

  @Override
  public void run(final ApplicationArguments args) {
    lock.lock();
    try {
      pending = true;
    } finally {
      lock.unlock();
    }
    final Mono<Integer> first =
        apply()
            .doOnNext(applied ->
                log.atInfo()
                    .addKeyValue("dir", dir)
                    .addKeyValue("sessions", applied)
                    .log("Bootstrap done"))
            .doFinally(_ -> firstPassDone());
    final Flux<Integer> pipeline =
        watch
            ? first.thenMany(Flux.interval(interval).onBackpressureDrop().concatMap(_ -> apply()))
            : first.flux();
    watcher = pipeline.subscribe(_ -> {}, e -> log.atError().setCause(e).log("Bootstrap ended"));
  }

  /** Keeps the readiness probe waiting while the first pass runs. */
  @EventListener
  /* default */ void onReadiness(final AvailabilityChangeEvent<ReadinessState> event) {
    lock.lock();
    try {
      if (pending && event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
        refused = true;
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
      }
    } finally {
      lock.unlock();
    }
  }

  private void firstPassDone() {
    lock.lock();
    try {
      pending = false;
      if (refused) {
        refused = false;
        AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
      }
    } finally {
      lock.unlock();
    }
  }

  /** Stops the watch. */
  @PreDestroy
  /* default */ void stop() {
    if (watcher != null) {
      watcher.dispose();
    }
  }

  /**
   * Apply the files that are new or changed.
   *
   * @return how many sessions were applied
   */
  /* default */ Mono<Integer> apply() {
    return Mono.fromCallable(this::changedFiles)
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(Flux::fromIterable)
        .concatMap(this::applyFile)
        .filter(applied -> applied)
        .count()
        .map(Long::intValue);
  }

  private List<Path> changedFiles() {
    return files().stream().filter(this::changed).toList();
  }

  /** Whether the file is new or was modified since it was last looked at; records the time. */
  private boolean changed(final Path file) {
    try {
      final FileTime time = Files.getLastModifiedTime(file);
      return !time.equals(seen.put(file, time));
    } catch (final IOException e) {
      log.atWarn().setCause(e).addKeyValue("file", file).log("Could not read the file's time");
      return false;
    }
  }

  private List<Path> files() {
    List<Path> files = List.of();
    if (Files.isDirectory(dir)) {
      try (var stream = Files.list(dir)) {
        files =
            stream
                .filter(path -> path.toString().endsWith(".json"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
      } catch (final IOException e) {
        log.atWarn().setCause(e).addKeyValue("dir", dir).log("Could not list the bootstrap folder");
      }
    } else {
      log.atWarn().addKeyValue("dir", dir).log("The bootstrap folder does not exist");
    }
    return files;
  }

  private Mono<Boolean> applyFile(final Path file) {
    return Mono.fromCallable(() -> read(file))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(request ->
            sessionService
                .applyConfig(sessionMapper.configRequestToSessionConfigData(request))
                .timeout(APPLY_TIMEOUT)
                .thenReturn(true))
        .defaultIfEmpty(false)
        .onErrorResume(e -> {
          log.atWarn().setCause(e).addKeyValue("file", file).log("Skipped, could not apply");
          return Mono.just(false);
        });
  }

  /** The valid request in the file, or null (logged) if it is not valid. */
  private @Nullable ConfigRequest read(final Path file) throws IOException {
    final ConfigRequest request = mapper.readValue(Files.readString(file), ConfigRequest.class);
    final Set<ConstraintViolation<ConfigRequest>> problems = validator.validate(request);
    if (problems.isEmpty()) {
      return request;
    }
    log.atWarn().addKeyValue("file", file).log("Skipped, invalid: {}", problems);
    return null;
  }
}
