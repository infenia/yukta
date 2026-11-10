// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCommonSettings;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * The rules document of a {@code rules} supervisor: its settings without the settings every engine
 * shares ({@link SupervisorCommonSettings}). REST, MCP and the CLI read, replace and replay it
 * here, so a rules edit never drops {@code allowedCommands}, {@code initiative} or the like.
 */
@UtilityClass
public class SupervisorRules {

  /** The engine whose settings are a rules document. */
  public static final String TYPE = "rules";

  /** The settings every engine shares, which are not part of a rules document. */
  private static final Set<String> COMMON =
      SupervisorCommonSettings.SCHEMA.stream()
          .map(ConfigField::name)
          .collect(Collectors.toUnmodifiableSet());

  /**
   * A rules document as stored, secrets not hidden.
   *
   * @param version the newest kept version of the supervisor; 0 when none is kept
   * @param rules the document ({@code schemaVersion}, {@code rules}, ...)
   */
  public record Document(int version, Map<String, Object> rules) {

    /** Keeps an unmodifiable copy. */
    public Document {
      rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }
  }

  /**
   * The rules document of a supervisor.
   *
   * @param service the supervisors
   * @param id the supervisor id
   * @return the document; 404 when there is no such supervisor, 409 when it has another engine
   */
  public static Mono<Document> read(final SupervisorService service, final String id) {
    return find(service, id).flatMap(supervisor -> document(service, supervisor));
  }

  /**
   * Replaces the rules document, keeping the common settings and every secret sent as {@code ***}.
   * The save validates, keeps a version and restarts the supervisor.
   *
   * @param service the supervisors
   * @param id the supervisor id
   * @param rules the new document
   * @return the document as saved; 400 when it names a common setting
   */
  public static Mono<Document> save(
      final SupervisorService service, final String id, final Map<String, Object> rules) {
    return Mono.defer(
        () -> {
          refuseCommon(id, rules);
          return find(service, id)
              .flatMap(
                  existing ->
                      service.saveSupervisor(
                          existing.withEngine(existing.type(), merged(existing, rules))))
              .flatMap(saved -> document(service, saved));
        });
  }

  /**
   * Shows what a supervisor would have done on a past run, with a draft document in place of the
   * stored one and the stored common settings, in dry run.
   *
   * @param service the supervisors
   * @param id the supervisor id
   * @param executionId the past run
   * @param draft the draft document; {@code null} replays the stored rules
   * @return the decisions it would have made
   */
  public static Mono<List<SupervisorService.ReplayDecision>> replay(
      final SupervisorService service,
      final String id,
      final String executionId,
      final @Nullable Map<String, Object> draft) {
    return Mono.defer(
        () -> {
          if (draft != null) {
            refuseCommon(id, draft);
          }
          return find(service, id)
              .flatMap(
                  existing ->
                      service.replay(
                          id, draft == null ? null : merged(existing, draft), executionId));
        });
  }

  private static Mono<Supervisor> find(final SupervisorService service, final String id) {
    return service
        .supervisors()
        .filter(found -> found.id().equals(id))
        .next()
        .switchIfEmpty(Mono.error(new NotFoundException("Supervisor not found: " + id)))
        .filter(found -> TYPE.equals(found.type()))
        .switchIfEmpty(
            Mono.error(
                new ConflictException(
                    "Supervisor " + id + " does not use the " + TYPE + " engine")));
  }

  private static Mono<Document> document(
      final SupervisorService service, final Supervisor supervisor) {
    return service
        .versions(supervisor.id())
        .map(
            versions ->
                new Document(
                    versions.isEmpty() ? 0 : versions.getFirst().version(),
                    copyWhere(supervisor.settings(), key -> !COMMON.contains(key))));
  }

  private static void refuseCommon(final String id, final Map<String, Object> rules) {
    final List<String> common = rules.keySet().stream().filter(COMMON::contains).sorted().toList();
    if (!common.isEmpty()) {
      throw new IllegalArgumentException(
          "Not part of the rules, change them on the supervisor " + id + ": " + common);
    }
  }

  /** The common settings of the supervisor, then the new rules (a {@code ***} keeps a secret). */
  private static Map<String, Object> merged(
      final Supervisor existing, final Map<String, Object> rules) {
    final Map<String, Object> settings = copyWhere(existing.settings(), COMMON::contains);
    settings.putAll(SupervisorSettingsMask.keepHidden(rules, existing.settings()));
    return settings;
  }

  /** A copy of the settings whose keys pass the test, in their order; null values are kept. */
  private static Map<String, Object> copyWhere(
      final Map<String, Object> settings, final Predicate<String> keep) {
    return settings.entrySet().stream()
        .filter(entry -> keep.test(entry.getKey()))
        .collect(
            LinkedHashMap::new,
            (copy, entry) -> copy.put(entry.getKey(), entry.getValue()),
            Map::putAll);
  }
}
