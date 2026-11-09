// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.AssignmentView;
import com.infenia.yukta.dto.response.SupervisorValidation;
import com.infenia.yukta.dto.response.SupervisorView;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.supervisor.SupervisorAssignments;
import com.infenia.yukta.service.supervisor.SupervisorDecision;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorInfo;
import com.infenia.yukta.service.supervisor.SupervisorRules;
import com.infenia.yukta.service.supervisor.SupervisorService;
import com.infenia.yukta.service.supervisor.SupervisorSettingsMask;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Configured supervisors, the attachments that run them in a scope, their live status and their
 * decisions. Reading needs a viewer key; creating, changing and removing needs an admin key.
 * Settings are never echoed in full: SECRET fields and values under keys that look like secrets are
 * replaced by {@code ***}, and a value sent back as {@code ***} keeps the stored one.
 */
@RestController
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.GodClass",
  "PMD.LinguisticNaming",
  "PMD.TooManyMethods"
})
@RequestMapping("/api/supervisors")
@RequiredArgsConstructor
@Tag(name = "Supervisors API", description = "Supervisors, what they own and their decisions")
@RegisterReflectionForBinding({
  SupervisorDecision.class,
  SupervisorInfo.class,
  SupervisorsController.SupervisorBody.class,
  SupervisorsController.AssignmentBody.class,
  SupervisorsController.ValidationView.class,
  SupervisorsController.VersionView.class,
  SupervisorsController.RulesView.class,
  SupervisorsController.ReplayBody.class,
  SupervisorService.ReplayDecision.class
})
public class SupervisorsController {

  /** Most decisions one request may ask for. */
  private static final int MAX_DECISIONS = 500;

  /** The reason a read-only attachment or supervisor is refused. */
  private static final String SYSTEM_MESSAGE =
      "A system supervisor is configured by the server and cannot be changed here";

  /** Creates, changes and removes supervisors and their attachments. */
  private final SupervisorService service;

  /** Knows what runs and what supervisors decided. */
  private final SupervisorHost host;

  /** What supervisors own, set as a whole. */
  private final SupervisorAssignments assignments;

  /**
   * A supervisor as sent.
   *
   * @param id the supervisor id; generated when missing on create
   * @param type the supervisor type (see {@code /api/supervisor-types})
   * @param description what it is for
   * @param settings the settings; a value of {@code ***} keeps the stored secret
   * @param dryRun whether commands are only recorded; false when missing
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "the compact constructor stores an unmodifiable copy")
  public record SupervisorBody(
      String id,
      String type,
      String description,
      Map<String, Object> settings,
      @Nullable Boolean dryRun,
      @Nullable List<String> parents,
      @Nullable Map<String, DelegationBody> delegation) {

    /**
     * How one kind of delegation is asked.
     *
     * @param mode ORDERED, ALL or ANY
     * @param timeoutSeconds how long one parent has to answer; the default when absent
     */
    public record DelegationBody(@Nullable String mode, @Nullable Long timeoutSeconds) {}

    /** Keeps an immutable copy of the settings. */
    public SupervisorBody {
      // Map.copyOf rejects null values, and a JSON setting may be null
      settings =
          settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }
  }

  /**
   * One thing a supervisor should own, as sent.
   *
   * @param target what it owns: server, session:<id>, workflow:<s>/<w>, run:<s>/<w> or a section
   * @param enabled whether it runs; true when missing
   * @param locked whether only a lock holder may change or remove it; false when missing
   */
  public record AssignmentBody(
      String target, @Nullable Boolean enabled, @Nullable Boolean locked) {}

  // ---- supervisors

  /**
   * List the configured supervisors, those configured as beans included.
   *
   * @return the supervisors, secrets hidden, with their attachment counts and command counts
   */
  @GetMapping
  @Operation(summary = "List supervisors")
  public Mono<ApiResponse<List<SupervisorView>>> list() {
    return Mono.zip(service.supervisors().collectList(), service.attachments().collectList())
        .map(
            tuple -> {
              final Map<String, SupervisorInfo> running = running();
              final List<SupervisorView> views =
                  Stream.concat(
                          tuple.getT1().stream().map(s -> view(s, tuple.getT2(), running)),
                          systemInfos().map(this::systemView))
                      .toList();
              return ApiResponse.success(200, "Supervisors retrieved", views);
            });
  }

  /**
   * Get one supervisor.
   *
   * @param id the supervisor id
   * @return the supervisor; 404 if unknown
   */
  @GetMapping("/{id}")
  @Operation(summary = "Get a supervisor")
  public Mono<ApiResponse<SupervisorView>> get(@PathVariable final String id) {
    return Mono.zip(
            find(id).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()),
            service.attachments().collectList())
        .map(
            tuple -> {
              if (tuple.getT1().isPresent()) {
                return view(tuple.getT1().get(), tuple.getT2(), running());
              }
              return systemInfos()
                  .filter(info -> info.id().equals(id))
                  .findFirst()
                  .map(this::systemView)
                  .orElseThrow(() -> new NotFoundException("Supervisor not found: " + id));
            })
        .map(view -> ApiResponse.success(200, "Supervisor retrieved", view));
  }

  /**
   * Create a supervisor.
   *
   * @param body the supervisor
   * @return the supervisor; 409 if the id exists, 400 for an unknown type or invalid settings
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a supervisor")
  public Mono<ApiResponse<SupervisorView>> create(@RequestBody final SupervisorBody body) {
    return Mono.fromSupplier(() -> draft(idOrNew(body.id()), body, body.settings()))
        .flatMap(
            draft ->
                find(draft.id())
                    .hasElement()
                    .flatMap(
                        exists ->
                            exists || host.isBuiltIn(draft.id())
                                ? Mono.error(
                                    new ConflictException("Supervisor exists: " + draft.id()))
                                : service.saveSupervisor(draft)))
        .map(saved -> ApiResponse.success(201, "Supervisor created", shown(saved)));
  }

  /**
   * Change a supervisor; attachments that use it restart with the new settings.
   *
   * @param id the supervisor id
   * @param body the new supervisor; its id is ignored
   * @return the supervisor; 409 for a system supervisor
   */
  @PutMapping("/{id}")
  @Operation(summary = "Replace a supervisor")
  public Mono<ApiResponse<SupervisorView>> replace(
      @PathVariable final String id, @RequestBody final SupervisorBody body) {
    return find(id)
        .map(existing -> SupervisorSettingsMask.keepHidden(body.settings(), existing.settings()))
        .defaultIfEmpty(body.settings())
        .flatMap(
            settings ->
                host.isBuiltIn(id)
                    ? Mono.error(new ConflictException(SYSTEM_MESSAGE))
                    : service.saveSupervisor(draft(id, body, settings)))
        .map(saved -> ApiResponse.success(200, "Supervisor saved", shown(saved)));
  }

  /**
   * The rules document of a rules supervisor, with secret settings hidden.
   *
   * @param id the supervisor id
   * @return the rules and the newest version; 404 if unknown, 409 if it has another engine
   */
  @GetMapping("/{id}/engine/rules")
  @Operation(summary = "Read the rules document of a rules supervisor")
  public Mono<ApiResponse<RulesView>> rules(@PathVariable final String id) {
    return SupervisorRules.read(service, id)
        .map(doc -> ApiResponse.success(200, "Rules", view(doc)));
  }

  /**
   * Replace the rules of a rules supervisor and swap the running rules.
   *
   * @param id the supervisor id
   * @param rules the new rules document, without the common settings
   * @return the saved rules and their newest version; 404 if unknown, 409 if built in or when it
   *     uses another engine
   */
  @PutMapping("/{id}/engine/rules")
  @Operation(
      summary = "Replace the rules document of a rules supervisor",
      description =
          "Validates the rules, keeps the supervisor's other settings, saves a new version and"
              + " swaps the running rules; the common settings (allowedCommands, initiative, ...)"
              + " change through PUT /api/supervisors/{id}")
  public Mono<ApiResponse<RulesView>> saveRules(
      @PathVariable final String id, @RequestBody final Map<String, Object> rules) {
    return host.isBuiltIn(id)
        ? Mono.error(new ConflictException(SYSTEM_MESSAGE))
        : SupervisorRules.save(service, id, rules)
            .map(doc -> ApiResponse.success(200, "Rules saved", view(doc)));
  }

  /**
   * Try a draft rules document on the recorded events of a past run, with the supervisor's stored
   * common settings, in dry run: nothing is sent.
   *
   * @param id the supervisor id
   * @param body the past run and, in {@code settings}, the draft rules document (none: the stored)
   * @return what the rules would have done, and whether it would have been allowed
   */
  @PostMapping("/{id}/engine/rules/replay")
  @Operation(summary = "Replay a draft rules document over a past run")
  public Mono<ApiResponse<List<SupervisorService.ReplayDecision>>> replayRules(
      @PathVariable final String id, @RequestBody final ReplayBody body) {
    return SupervisorRules.replay(service, id, body.executionId(), body.settings())
        .map(found -> ApiResponse.success(200, "Replayed", found));
  }

  /** The document as shown: secrets hidden. */
  private RulesView view(final SupervisorRules.Document doc) {
    return new RulesView(
        doc.version(),
        SupervisorSettingsMask.hide(doc.rules(), service.secretSettings(SupervisorRules.TYPE)));
  }

  /**
   * The rules document of a rules supervisor.
   *
   * @param version the newest saved version of the supervisor; 0 when none is kept
   * @param rules the document ({@code schemaVersion}, {@code rules}), without the common settings
   */
  public record RulesView(int version, Map<String, Object> rules) {

    /** Keeps an unmodifiable copy, so the caller's map cannot change the view. */
    public RulesView {
      rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }
  }

  /**
   * The saved versions of a supervisor (the newest 20), newest first.
   *
   * @param id the supervisor id
   * @return the versions, with secret settings hidden; 404 if unknown
   */
  @GetMapping("/{id}/versions")
  @Operation(summary = "List the saved versions of a supervisor")
  public Mono<ApiResponse<List<VersionView>>> versions(@PathVariable final String id) {
    return service
        .versions(id)
        .map(
            found ->
                ApiResponse.success(
                    200,
                    "Versions",
                    found.stream()
                        .map(v -> new VersionView(v.version(), v.savedAt(), shown(v.supervisor())))
                        .toList()));
  }

  /**
   * Make an earlier version current again; it is saved as a new version and the attachments that
   * use the supervisor restart with it.
   *
   * @param id the supervisor id
   * @param version the number of the version
   * @return the supervisor; 404 if the version is no longer kept
   */
  @PostMapping("/{id}/rollback")
  @Operation(summary = "Roll a supervisor back to a saved version")
  public Mono<ApiResponse<SupervisorView>> rollback(
      @PathVariable final String id, @RequestParam final int version) {
    return host.isBuiltIn(id)
        ? Mono.error(new ConflictException(SYSTEM_MESSAGE))
        : service
            .rollback(id, version)
            .map(saved -> ApiResponse.success(200, "Supervisor rolled back", shown(saved)));
  }

  /**
   * Try a supervisor on the recorded events of a past run, in dry run: nothing is sent.
   *
   * @param id the supervisor id
   * @param body the past run and, optionally, draft settings to try instead of the stored ones
   * @return what the supervisor would have done, and whether it would have been allowed
   */
  @PostMapping("/{id}/replay")
  @Operation(summary = "Replay a supervisor over a past run")
  public Mono<ApiResponse<List<SupervisorService.ReplayDecision>>> replay(
      @PathVariable final String id, @RequestBody final ReplayBody body) {
    return service
        .replay(id, body.settings(), body.executionId())
        .map(found -> ApiResponse.success(200, "Replayed", found));
  }

  /**
   * A replay request.
   *
   * @param executionId the past run
   * @param settings draft settings to try, or {@code null} for the stored ones
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "the compact constructor stores an unmodifiable copy")
  public record ReplayBody(String executionId, @Nullable Map<String, Object> settings) {

    /** Keeps an unmodifiable copy of the draft settings; a setting may be null. */
    public ReplayBody {
      if (settings != null) {
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
      }
    }
  }

  /**
   * A saved version.
   *
   * @param version the number
   * @param savedAt when it was saved
   * @param supervisor the supervisor as saved
   */
  public record VersionView(int version, java.time.Instant savedAt, SupervisorView supervisor) {}

  /**
   * Delete a supervisor that no attachment uses and no definition declares.
   *
   * @param id the supervisor id
   * @return nothing; 404 if unknown, 409 if attached or a system supervisor
   */
  @DeleteMapping("/{id}")
  @Operation(summary = "Delete a supervisor")
  public Mono<ApiResponse<Void>> delete(@PathVariable final String id) {
    if (host.isBuiltIn(id)) {
      return Mono.error(new ConflictException(SYSTEM_MESSAGE));
    }
    return service
        .deleteSupervisor(id)
        .then(Mono.fromSupplier(() -> ApiResponse.success(200, "Supervisor deleted", null)));
  }

  /**
   * Check a draft supervisor without saving it.
   *
   * @param body the draft
   * @return whether saving would be accepted, and the problems by field
   */
  @PostMapping("/validate")
  @Operation(
      summary = "Validate a supervisor",
      description =
          "Checks the type, the settings and what the type itself checks, without saving. Always"
              + " 200: look at valid and errors.")
  public Mono<ApiResponse<SupervisorValidation>> validate(@RequestBody final SupervisorBody body) {
    return Mono.fromSupplier(() -> body.id() == null || body.id().isBlank() ? "draft" : body.id())
        .flatMap(
            id ->
                find(id)
                    .map(
                        existing ->
                            SupervisorSettingsMask.keepHidden(body.settings(), existing.settings()))
                    .defaultIfEmpty(body.settings())
                    .flatMap(
                        settings ->
                            service.check(
                                new Supervisor(
                                    id,
                                    required("type", body.type()),
                                    settings,
                                    Boolean.TRUE.equals(body.dryRun()),
                                    body.description()))))
        .map(
            issues ->
                new SupervisorValidation(
                    issues.isEmpty(),
                    issues.stream()
                        .map(i -> new SupervisorValidation.FieldIssue(i.field(), i.message()))
                        .toList()))
        .map(result -> ApiResponse.success(200, "Supervisor checked", result));
  }

  // ---- assignments

  /**
   * What one supervisor owns: its assignments, whatever made them.
   *
   * @param id the supervisor id
   * @return its assignments
   */
  @GetMapping("/{id}/assignments")
  @Operation(summary = "List what a supervisor owns")
  public Mono<ApiResponse<List<AssignmentView>>> assignmentsOf(@PathVariable final String id) {
    return assignmentViews()
        .map(all -> all.stream().filter(a -> a.supervisorId().equals(id)).toList())
        .map(list -> ApiResponse.success(200, "Assignments retrieved", list));
  }

  /**
   * Makes a supervisor own exactly these targets. Everything is checked first (each target, the
   * one-owner rule, the locks); nothing changes when a check fails.
   *
   * @param id the supervisor id
   * @param unlock whether locked assignments may be changed or removed
   * @param body what it should own
   * @return what it owns afterwards; 400 for a bad target, 404 for an unknown supervisor, 409 for a
   *     target another supervisor owns ({@code OWNERSHIP_CONFLICT}) or a locked assignment
   */
  @PutMapping("/{id}/assignments")
  @Operation(
      summary = "Set what a supervisor owns",
      description =
          "Replaces the assignments made here (MANUAL); those from definitions change with the"
              + " definition. A target is server, session:<id>, workflow:<s>/<w>, run:<s>/<w> or"
              + " workflow:<s>/<w>//<selector>,... (node ids, <prefix>.*, group:<name>).")
  public Mono<ApiResponse<List<AssignmentView>>> setAssignments(
      @PathVariable final String id,
      @RequestParam(defaultValue = "false") final boolean unlock,
      @RequestBody final List<AssignmentBody> body) {
    if (host.isBuiltIn(id)) {
      return Mono.error(new ConflictException(SYSTEM_MESSAGE));
    }
    return Mono.fromSupplier(() -> wanted(body))
        .flatMap(wanted -> assignments.set(id, wanted, unlock))
        .map(
            saved ->
                ApiResponse.success(
                    200,
                    "Assignments saved",
                    saved.stream().map(SupervisorsController::assignmentView).toList()));
  }

  /**
   * What would be wrong with setting these assignments, without changing anything.
   *
   * @param id the supervisor id
   * @param body what it should own
   * @return {@code valid} and the problems, each with a field {@code assignments[i]}
   */
  @PostMapping("/{id}/assignments/check")
  @Operation(summary = "Check what a supervisor would own, without saving")
  public Mono<ApiResponse<ValidationView>> checkAssignments(
      @PathVariable final String id, @RequestBody final List<AssignmentBody> body) {
    return Mono.fromSupplier(() -> wanted(body))
        .flatMap(wanted -> assignments.check(id, wanted))
        .map(
            issues ->
                ApiResponse.success(
                    200,
                    issues.isEmpty() ? "No problems" : "Problems found",
                    new ValidationView(
                        issues.isEmpty(),
                        issues.stream().map(i -> i.field() + ": " + i.message()).toList())));
  }

  /**
   * Every assignment. With {@code session} or {@code workflow} it lists who owns something there.
   *
   * @param session only assignments that apply to this session
   * @param workflow only assignments that apply to this workflow
   * @param source only this source: {@code MANUAL}, {@code DEFINITION} or {@code SYSTEM}
   * @return the assignments
   */
  @GetMapping("/assignments")
  @Operation(summary = "List assignments")
  public Mono<ApiResponse<List<AssignmentView>>> assignments(
      @RequestParam(required = false) final @Nullable String session,
      @RequestParam(required = false) final @Nullable String workflow,
      @RequestParam(required = false) final @Nullable String source) {
    return assignmentViews()
        .map(
            all ->
                all.stream()
                    .filter(a -> source == null || a.source().equalsIgnoreCase(source))
                    .filter(a -> applies(SupervisorScope.parse(a.scope()), session, workflow))
                    .toList())
        .map(list -> ApiResponse.success(200, "Assignments retrieved", list));
  }

  /**
   * The result of a check.
   *
   * @param valid whether nothing is wrong
   * @param problems each as {@code field: message}
   */
  public record ValidationView(boolean valid, List<String> problems) {

    /** Keeps an immutable copy of the problems. */
    public ValidationView {
      problems = List.copyOf(problems);
    }
  }

  private static List<SupervisorAssignments.Assignment> wanted(final List<AssignmentBody> body) {
    if (body == null) {
      throw new IllegalArgumentException("A list of assignments is required");
    }
    return body.stream()
        .map(
            a -> {
              final String target = a.target();
              if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("target is required");
              }
              final Boolean enabled = a.enabled();
              return new SupervisorAssignments.Assignment(
                  SupervisorScope.parse(target),
                  enabled == null || enabled,
                  Boolean.TRUE.equals(a.locked()));
            })
        .toList();
  }

  // ---- runtime

  /**
   * List what runs on the control bus.
   *
   * @return one entry per running supervisor and control-plane plugin, in review order, with its
   *     scope and command counts since startup
   */
  @GetMapping("/status")
  @Operation(
      summary = "Supervisor runtime status",
      description =
          "Lists the supervisors and control-plane plugins running on the control bus, in review"
              + " order, with how many commands each applied, had refused, only recorded (dry-run)"
              + " or dropped (rate limit) since startup")
  public Mono<ApiResponse<List<SupervisorInfo>>> status() {
    return Mono.fromCallable(host::status)
        .map(status -> ApiResponse.success(200, "Supervisor status retrieved", status));
  }

  /**
   * The newest decisions about the commands supervisors emitted since startup.
   *
   * @param supervisor only this supervisor (all its attachments)
   * @param attachment only this attachment
   * @param limit most entries, 1 to 500; 50 by default
   * @return the decisions, newest first
   */
  @GetMapping("/decisions")
  @Operation(summary = "Recent supervisor decisions")
  public Mono<ApiResponse<List<SupervisorDecision>>> decisions(
      @RequestParam(required = false) final @Nullable String supervisor,
      @RequestParam(required = false) final @Nullable String attachment,
      @RequestParam(defaultValue = "50") final int limit) {
    final int most = Math.min(MAX_DECISIONS, Math.max(1, limit));
    if (attachment != null || supervisor == null) {
      return Mono.fromSupplier(
          () -> ApiResponse.success(200, "Decisions retrieved", host.decisions(attachment, most)));
    }
    return service
        .attachments()
        .filter(a -> a.supervisorId().equals(supervisor))
        .map(SupervisorAttachment::id)
        .concatWith(Mono.just(supervisor))
        .distinct()
        .flatMapIterable(id -> host.decisions(id, most))
        .sort(Comparator.comparing(SupervisorDecision::time).reversed())
        .take(most)
        .collectList()
        .map(list -> ApiResponse.success(200, "Decisions retrieved", list));
  }

  // ---- helpers

  private Mono<Supervisor> find(final String id) {
    return service.supervisors().filter(s -> s.id().equals(id)).next();
  }

  private Mono<List<AssignmentView>> assignmentViews() {
    return service
        .attachments()
        .map(SupervisorsController::assignmentView)
        .collectList()
        .map(
            stored ->
                Stream.concat(
                        stored.stream(), systemInfos().map(SupervisorsController::systemAttachment))
                    .toList());
  }

  private Map<String, SupervisorInfo> running() {
    return host.status().stream()
        .collect(Collectors.toMap(SupervisorInfo::id, Function.identity(), (a, b) -> a));
  }

  /** The supervisors configured as beans: running, with an id no stored attachment owns. */
  private Stream<SupervisorInfo> systemInfos() {
    return host.status().stream().filter(info -> host.isBuiltIn(info.id()));
  }

  private SupervisorView view(
      final Supervisor supervisor,
      final List<SupervisorAttachment> all,
      final Map<String, SupervisorInfo> running) {
    final List<SupervisorInfo> infos =
        all.stream()
            .filter(a -> a.supervisorId().equals(supervisor.id()))
            .map(a -> running.get(a.id()))
            .filter(Objects::nonNull)
            .toList();
    return new SupervisorView(
        supervisor.id(),
        supervisor.type(),
        supervisor.description(),
        SupervisorSettingsMask.hide(
            supervisor.settings(), service.secretSettings(supervisor.type())),
        supervisor.dryRun(),
        false,
        (int) all.stream().filter(a -> a.supervisorId().equals(supervisor.id())).count(),
        infos.stream().mapToLong(SupervisorInfo::applied).sum(),
        infos.stream().mapToLong(SupervisorInfo::rejected).sum(),
        infos.stream().mapToLong(SupervisorInfo::recorded).sum(),
        infos.stream().mapToLong(SupervisorInfo::rateLimited).sum(),
        supervisor.parents(),
        delegationView(supervisor));
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private static Map<String, SupervisorView.DelegationView> delegationView(
      final Supervisor supervisor) {
    final Map<String, SupervisorView.DelegationView> view = new java.util.TreeMap<>();
    supervisor
        .delegation()
        .forEach(
            (kind, d) ->
                view.put(
                    kind.name(),
                    new SupervisorView.DelegationView(d.mode().name(), d.timeout().toSeconds())));
    return view;
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private static Map<
          com.infenia.yukta.plugin.control.DelegationKind,
          com.infenia.yukta.plugin.control.Delegation>
      delegationOf(final @Nullable Map<String, SupervisorBody.DelegationBody> body) {
    final Map<
            com.infenia.yukta.plugin.control.DelegationKind,
            com.infenia.yukta.plugin.control.Delegation>
        out = new java.util.EnumMap<>(com.infenia.yukta.plugin.control.DelegationKind.class);
    if (body != null) {
      body.forEach(
          (kind, d) -> {
            final String mode = d.mode();
            final Long timeoutSeconds = d.timeoutSeconds();
            try {
              out.put(
                  com.infenia.yukta.plugin.control.DelegationKind.valueOf(
                      kind.toUpperCase(java.util.Locale.ROOT)),
                  new com.infenia.yukta.plugin.control.Delegation(
                      mode == null
                          ? com.infenia.yukta.plugin.control.DelegationMode.ORDERED
                          : com.infenia.yukta.plugin.control.DelegationMode.valueOf(
                              mode.toUpperCase(java.util.Locale.ROOT)),
                      timeoutSeconds == null
                          ? com.infenia.yukta.plugin.control.Delegation.DEFAULT_TIMEOUT
                          : java.time.Duration.ofSeconds(timeoutSeconds)));
            } catch (IllegalArgumentException e) {
              throw new IllegalArgumentException(
                  "delegation: unknown kind or mode, or a bad timeout (kinds APPROVAL, INPUT,"
                      + " CONSULT; modes ORDERED, ALL, ANY)",
                  e);
            }
          });
    }
    return out;
  }

  private SupervisorView systemView(final SupervisorInfo info) {
    return new SupervisorView(
        info.id(),
        info.type(),
        null,
        Map.of(),
        info.dryRun(),
        true,
        1,
        info.applied(),
        info.rejected(),
        info.recorded(),
        info.rateLimited(),
        List.of(),
        Map.of());
  }

  /** The supervisor as saved, shown without its secrets and without counts. */
  private SupervisorView shown(final Supervisor saved) {
    return view(saved, List.of(), Map.of());
  }

  private static AssignmentView assignmentView(final SupervisorAttachment a) {
    return new AssignmentView(
        a.id(),
        a.supervisorId(),
        a.scope().key(),
        a.enabled(),
        a.source().name(),
        a.originSessionId(),
        a.originWorkflowId(),
        a.conflict(),
        a.locked());
  }

  private static AssignmentView systemAttachment(final SupervisorInfo info) {
    final String scope =
        switch (info.scope()) {
          case "SESSION" -> "session:" + info.scopeSessionId();
          case "WORKFLOW", "SECTION" ->
              "workflow:"
                  + (info.scopeSessionId() == null ? "" : info.scopeSessionId() + "/")
                  + info.scopeWorkflowId();
          default -> "global";
        };
    return new AssignmentView(
        info.id(),
        info.id(),
        scope,
        true,
        SupervisorSource.SYSTEM.name(),
        null,
        null,
        false,
        false);
  }

  /** Whether runs of the session and workflow fall in the scope; no filter means everything. */
  private static boolean applies(
      final SupervisorScope scope,
      final @Nullable String session,
      final @Nullable String workflow) {
    if (session == null && workflow == null) {
      return true;
    }
    final String scopeSession = scope.sessionId();
    return switch (scope.kind()) {
      case GLOBAL -> true;
      case SESSION -> session != null && session.equals(scopeSession);
      case SERVER -> false;
      case WORKFLOW, RUN, SECTION ->
          inWorkflow(scopeSession, scope.workflowId(), session, workflow);
    };
  }

  /** Whether a workflow scope falls in the filter; a session-only filter takes its workflows. */
  private static boolean inWorkflow(
      final @Nullable String scopeSession,
      final @Nullable String scopeWorkflow,
      final @Nullable String session,
      final @Nullable String workflow) {
    if (workflow == null) {
      return scopeSession != null && scopeSession.equals(session);
    }
    return workflow.equals(scopeWorkflow)
        && (scopeSession == null || session == null || scopeSession.equals(session));
  }

  private static Supervisor draft(
      final String id, final SupervisorBody body, final Map<String, Object> settings) {
    return new Supervisor(
        id,
        required("type", body.type()),
        settings,
        Boolean.TRUE.equals(body.dryRun()),
        body.description(),
        body.parents() == null ? List.of() : body.parents(),
        delegationOf(body.delegation()));
  }

  private static String idOrNew(final @Nullable String id) {
    return id == null || id.isBlank() ? UUID.randomUUID().toString() : id.strip();
  }

  /** A missing or blank field is a client error, not a server one. */
  private static String required(final String name, final @Nullable String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
