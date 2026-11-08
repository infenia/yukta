// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.AttachmentView;
import com.infenia.yukta.dto.response.SupervisorValidation;
import com.infenia.yukta.dto.response.SupervisorView;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.supervisor.SupervisorDecision;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorInfo;
import com.infenia.yukta.service.supervisor.SupervisorService;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.GodClass"
})
@RequestMapping("/api/supervisors")
@RequiredArgsConstructor
@Tag(name = "Supervisors API", description = "Supervisors, their attachments and their decisions")
@RegisterReflectionForBinding({
  SupervisorDecision.class,
  SupervisorInfo.class,
  SupervisorsController.SupervisorBody.class,
  SupervisorsController.AttachmentBody.class,
  SupervisorsController.EnabledBody.class
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
      @Nullable Boolean dryRun) {

    /** Keeps an immutable copy of the settings. */
    public SupervisorBody {
      // Map.copyOf rejects null values, and a JSON setting may be null
      settings =
          settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }
  }

  /**
   * A manual attachment as sent.
   *
   * @param id the attachment id; generated when missing
   * @param supervisorId the supervisor to run
   * @param scope the scope key
   * @param enabled whether it runs; true when missing
   */
  public record AttachmentBody(String id, String supervisorId, String scope, Boolean enabled) {}

  /**
   * A change of an attachment.
   *
   * @param enabled whether it runs
   */
  public record EnabledBody(Boolean enabled) {}

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

  // ---- attachments

  /**
   * The attachments of one supervisor.
   *
   * @param id the supervisor id
   * @return its attachments
   */
  @GetMapping("/{id}/attachments")
  @Operation(summary = "List the attachments of a supervisor")
  public Mono<ApiResponse<List<AttachmentView>>> attachmentsOf(@PathVariable final String id) {
    return attachmentViews()
        .map(all -> all.stream().filter(a -> a.supervisorId().equals(id)).toList())
        .map(list -> ApiResponse.success(200, "Attachments retrieved", list));
  }

  /**
   * List attachments. With {@code session} or {@code workflow} it lists the <em>effective</em>
   * supervisors of that session or workflow: the global ones, those limited to the session, to the
   * workflow, and to sections of it.
   *
   * @param session only attachments that apply to this session
   * @param workflow only attachments that apply to this workflow
   * @param source only this source: {@code MANUAL}, {@code DEFINITION} or {@code SYSTEM}
   * @return the attachments
   */
  @GetMapping("/attachments")
  @Operation(summary = "List attachments")
  public Mono<ApiResponse<List<AttachmentView>>> attachments(
      @RequestParam(required = false) final @Nullable String session,
      @RequestParam(required = false) final @Nullable String workflow,
      @RequestParam(required = false) final @Nullable String source) {
    return attachmentViews()
        .map(
            all ->
                all.stream()
                    .filter(a -> source == null || a.source().equalsIgnoreCase(source))
                    .filter(a -> applies(SupervisorScope.parse(a.scope()), session, workflow))
                    .toList())
        .map(list -> ApiResponse.success(200, "Attachments retrieved", list));
  }

  /**
   * Attach a supervisor to a scope by hand. An enabled attachment starts at once.
   *
   * @param body the attachment
   * @return the attachment; 404 for an unknown supervisor, 400 for a bad scope, 409 at the limit or
   *     when the id belongs to a definition or system attachment
   */
  @PostMapping("/attachments")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Attach a supervisor",
      description =
          "Attach a supervisor to a scope. A section scope (workflow:<s>/<w>//build.*,deploy)"
              + " watches only those nodes and no workflow-wide event or command. Attachments made"
              + " here are MANUAL; those from a definition are changed by editing the definition.")
  public Mono<ApiResponse<AttachmentView>> attach(@RequestBody final AttachmentBody body) {
    return Mono.fromSupplier(
            () ->
                new SupervisorAttachment(
                    idOrNew(body.id()),
                    required("supervisorId", body.supervisorId()),
                    SupervisorScope.parse(required("scope", body.scope())),
                    body.enabled() == null || body.enabled()))
        .flatMap(
            attachment ->
                manual(attachment.id()).then(Mono.defer(() -> service.attach(attachment))))
        .map(saved -> ApiResponse.success(201, "Supervisor attached", attachmentView(saved)));
  }

  /**
   * Enable or disable a manual attachment.
   *
   * @param id the attachment id
   * @param body the new state
   * @return the attachment; 404 if unknown, 409 for a definition or system attachment
   */
  @PatchMapping("/attachments/{id}")
  @Operation(summary = "Enable or disable an attachment")
  public Mono<ApiResponse<AttachmentView>> patch(
      @PathVariable final String id, @RequestBody final EnabledBody body) {
    return manual(id)
        .then(Mono.defer(() -> service.attachments().filter(a -> a.id().equals(id)).next()))
        .switchIfEmpty(Mono.error(new NotFoundException("Attachment not found: " + id)))
        .flatMap(
            a ->
                service.attach(
                    new SupervisorAttachment(
                        a.id(),
                        a.supervisorId(),
                        a.scope(),
                        Boolean.TRUE.equals(required("enabled", body.enabled())),
                        a.source(),
                        a.originSessionId(),
                        a.originWorkflowId())))
        .map(saved -> ApiResponse.success(200, "Attachment saved", attachmentView(saved)));
  }

  /**
   * Detach a manual attachment; its supervisor stops without a restart.
   *
   * @param id the attachment id
   * @return nothing; 404 if unknown, 409 for a definition or system attachment
   */
  @DeleteMapping("/attachments/{id}")
  @Operation(summary = "Detach a supervisor")
  public Mono<ApiResponse<Void>> detach(@PathVariable final String id) {
    return manual(id)
        .then(Mono.defer(() -> service.detach(id)))
        .then(Mono.fromSupplier(() -> ApiResponse.success(200, "Supervisor detached", null)));
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

  /** Errors with 409 if the attachment exists and was not made by hand, or is a system one. */
  private Mono<Void> manual(final String id) {
    if (host.isBuiltIn(id)) {
      return Mono.error(new ConflictException(SYSTEM_MESSAGE));
    }
    return service
        .attachments()
        .filter(a -> a.id().equals(id) && a.source() != SupervisorSource.MANUAL)
        .next()
        .flatMap(
            a ->
                Mono.<Void>error(
                    new ConflictException(
                        "Attachment "
                            + id
                            + " comes from a "
                            + a.source()
                            + " source; edit the definition that declares it")));
  }

  private Mono<List<AttachmentView>> attachmentViews() {
    return service
        .attachments()
        .map(SupervisorsController::attachmentView)
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
        infos.stream().mapToLong(SupervisorInfo::rateLimited).sum());
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
        info.rateLimited());
  }

  /** The supervisor as saved, shown without its secrets and without counts. */
  private SupervisorView shown(final Supervisor saved) {
    return view(saved, List.of(), Map.of());
  }

  private static AttachmentView attachmentView(final SupervisorAttachment a) {
    return new AttachmentView(
        a.id(),
        a.supervisorId(),
        a.scope().key(),
        a.enabled(),
        a.source().name(),
        a.originSessionId(),
        a.originWorkflowId());
  }

  private static AttachmentView systemAttachment(final SupervisorInfo info) {
    final String scope =
        switch (info.scope()) {
          case "SESSION" -> "session:" + info.scopeSessionId();
          case "WORKFLOW", "SECTION" ->
              "workflow:"
                  + (info.scopeSessionId() == null ? "" : info.scopeSessionId() + "/")
                  + info.scopeWorkflowId();
          default -> "global";
        };
    return new AttachmentView(
        info.id(), info.id(), scope, true, SupervisorSource.SYSTEM.name(), null, null);
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
    final String scopeWorkflow = scope.workflowId();
    return switch (scope.kind()) {
      case GLOBAL -> true;
      case SESSION -> session != null && session.equals(scopeSession);
      case SERVER -> false;
      case WORKFLOW, RUN, SECTION ->
          workflow == null
              ? scopeSession != null && scopeSession.equals(session)
              : workflow.equals(scopeWorkflow)
                  && (scopeSession == null || session == null || scopeSession.equals(session));
    };
  }

  private static Supervisor draft(
      final String id, final SupervisorBody body, final Map<String, Object> settings) {
    return new Supervisor(
        id,
        required("type", body.type()),
        settings,
        Boolean.TRUE.equals(body.dryRun()),
        body.description());
  }

  private static String idOrNew(final @Nullable String id) {
    return id == null || id.isBlank() ? UUID.randomUUID().toString() : id.strip();
  }

  /** A missing or blank field is a client error, not a server one. */
  private static <T> T required(final String name, final @Nullable T value) {
    if (value == null || value instanceof String text && text.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
