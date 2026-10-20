// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.startup;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.mapper.SessionMapper;
import com.infenia.yukta.service.session.SessionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies every session file ({@code *.json}, the body of {@code POST /api/sessions}) in the folder
 * {@code yukta.bootstrap.dir} when the server starts, in name order. State kept in memory is lost
 * on restart, so this lets definitions live in files (for example in git) and come back by
 * themselves. A file that cannot be read, is invalid or cannot be applied is logged and skipped; it
 * never stops start-up.
 */
@Slf4j
@Component
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

  /**
   * Create the runner.
   *
   * @param dir the folder with the session files
   * @param mapper reads the files
   * @param validator checks the files
   * @param sessionMapper maps the request to the session model
   * @param sessionService applies the sessions
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // all are shared Spring beans
  public BootstrapRunner(
      @Value("${yukta.bootstrap.dir}") final String dir,
      final ObjectMapper mapper,
      final Validator validator,
      final SessionMapper sessionMapper,
      final SessionService sessionService) {
    this.dir = Path.of(dir);
    this.mapper = mapper;
    this.validator = validator;
    this.sessionMapper = sessionMapper;
    this.sessionService = sessionService;
  }

  @Override
  public void run(final ApplicationArguments args) {
    final int applied = apply();
    log.atInfo().addKeyValue("dir", dir).addKeyValue("sessions", applied).log("Bootstrap done");
  }

  /**
   * Apply the files.
   *
   * @return how many sessions were applied
   */
  /* default */ int apply() {
    int applied = 0;
    for (final Path file : files()) {
      if (applyFile(file)) {
        applied++;
      }
    }
    return applied;
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

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private boolean applyFile(final Path file) {
    boolean applied = false;
    try {
      final ConfigRequest request = mapper.readValue(Files.readString(file), ConfigRequest.class);
      final Set<ConstraintViolation<ConfigRequest>> problems = validator.validate(request);
      if (problems.isEmpty()) {
        sessionService
            .applyConfig(sessionMapper.configRequestToSessionConfigData(request))
            .block(APPLY_TIMEOUT);
        applied = true;
      } else {
        log.atWarn().addKeyValue("file", file).log("Skipped, invalid: {}", problems);
      }
    } catch (final IOException | RuntimeException e) {
      log.atWarn().setCause(e).addKeyValue("file", file).log("Skipped, could not apply");
    }
    return applied;
  }
}
