// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.supervisor.SupervisorStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Supervisor profiles and bindings in PostgreSQL (tables {@code supervisor_profile} and {@code
 * supervisor_binding}, migrations V7 and V8), so supervisors attached at run time come back after a
 * restart. Profile settings are stored as given, a model API key included.
 */
@Slf4j
public class R2dbcSupervisorStore implements SupervisorStore {

  /** The stored settings of a profile. */
  private static final TypeReference<Map<String, Object>> SETTINGS = new TypeReference<>() {};

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes the settings as JSON. */
  private final ObjectMapper mapper;

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param mapper reads and writes the settings
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // both are shared Spring beans
  public R2dbcSupervisorStore(final DatabaseClient client, final ObjectMapper mapper) {
    this.client = client;
    this.mapper = mapper;
  }

  /** Warns once at start-up about every supervisor id that migration V8 renamed. */
  @EventListener(ApplicationReadyEvent.class)
  public void warnRenamedIds() {
    client
        .sql("SELECT kind, old_id, new_id FROM supervisor_id_migration ORDER BY kind, old_id")
        .map(
            row ->
                new String[] {
                  row.get("kind", String.class),
                  row.get("old_id", String.class),
                  row.get("new_id", String.class)
                })
        .all()
        .subscribe(
            renamed ->
                log.atWarn()
                    .addKeyValue("kind", renamed[0])
                    .addKeyValue("oldId", renamed[1])
                    .addKeyValue("newId", renamed[2])
                    .log("A supervisor id broke the naming rule and was renamed by migration V8"),
            error -> log.atWarn().setCause(error).log("Could not read the renamed supervisor ids"));
  }

  @Override
  public Flux<SupervisorProfile> profiles() {
    return client
        .sql(
            "SELECT id, type, CAST(settings AS text) AS settings, dry_run FROM supervisor_profile"
                + " ORDER BY id")
        .map(
            row ->
                new SupervisorProfile(
                    row.get("id", String.class),
                    row.get("type", String.class),
                    mapper.readValue(row.get("settings", String.class), SETTINGS),
                    Boolean.TRUE.equals(row.get("dry_run", Boolean.class))))
        .all();
  }

  @Override
  public Mono<Void> saveProfile(final SupervisorProfile profile) {
    return Mono.fromCallable(() -> mapper.writeValueAsString(profile.settings()))
        .flatMap(
            settings ->
                client
                    .sql(
                        """
                        INSERT INTO supervisor_profile (id, type, settings, dry_run, updated_at)
                        VALUES (:id, :type, CAST(:settings AS jsonb), :dry, now())
                        ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type,
                          settings = EXCLUDED.settings, dry_run = EXCLUDED.dry_run,
                          updated_at = now()
                        """)
                    .bind("id", profile.id())
                    .bind("type", profile.type())
                    .bind("settings", settings)
                    .bind("dry", profile.dryRun())
                    .fetch()
                    .rowsUpdated())
        .then();
  }

  @Override
  public Mono<Boolean> deleteProfile(final String id) {
    return client
        .sql("DELETE FROM supervisor_profile WHERE id = :id")
        .bind("id", id)
        .fetch()
        .rowsUpdated()
        .map(rows -> rows > 0);
  }

  @Override
  public Flux<SupervisorBinding> bindings() {
    return client
        .sql("SELECT id, profile_id, scope, enabled FROM supervisor_binding ORDER BY id")
        .map(
            row ->
                new SupervisorBinding(
                    row.get("id", String.class),
                    row.get("profile_id", String.class),
                    SupervisorScope.parse(row.get("scope", String.class)),
                    Boolean.TRUE.equals(row.get("enabled", Boolean.class))))
        .all();
  }

  @Override
  public Mono<Void> saveBinding(final SupervisorBinding binding) {
    return client
        .sql(
            """
            INSERT INTO supervisor_binding (id, profile_id, scope, enabled, updated_at)
            VALUES (:id, :profile, :scope, :enabled, now())
            ON CONFLICT (id) DO UPDATE SET profile_id = EXCLUDED.profile_id,
              scope = EXCLUDED.scope, enabled = EXCLUDED.enabled, updated_at = now()
            """)
        .bind("id", binding.id())
        .bind("profile", binding.profileId())
        .bind("scope", binding.scope().key())
        .bind("enabled", binding.enabled())
        .fetch()
        .rowsUpdated()
        .then();
  }

  @Override
  public Mono<Boolean> deleteBinding(final String id) {
    return client
        .sql("DELETE FROM supervisor_binding WHERE id = :id")
        .bind("id", id)
        .fetch()
        .rowsUpdated()
        .map(rows -> rows > 0);
  }
}
