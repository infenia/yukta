// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.supervisor.SupervisorDecision;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Supervisor profiles and the bindings that attach them to a scope at run time.
 *
 * <p>Reading needs a viewer key; creating, changing and removing needs an admin key. Settings are
 * never echoed in full: values under keys that look like secrets are replaced by {@code ***}.
 */
@RestController
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveImports"})
@RequestMapping("/api/supervisors")
@RequiredArgsConstructor
@Tag(name = "Supervisor bindings API", description = "Attach supervisors to a scope at run time")
@RegisterReflectionForBinding({
  SupervisorDecision.class,
  SupervisorBindingsController.ProfileBody.class,
  SupervisorBindingsController.BindingBody.class
})
public class SupervisorBindingsController {

  /** Keys whose values are not shown. */
  private static final Pattern SECRET =
      Pattern.compile("(?i).*(key|secret|token|password|credential).*");

  /** Most decisions one request may ask for. */
  private static final int MAX_DECISIONS = 500;

  /** What replaces a secret value. */
  private static final String HIDDEN = "***";

  /** Attaches and detaches supervisors. */
  private final SupervisorService service;

  /** Knows what supervisors decided. */
  private final SupervisorHost host;

  /**
   * A profile as sent and shown.
   *
   * @param id the profile id
   * @param type the supervisor type
   * @param settings the settings; secret values are shown as {@code ***}
   * @param dryRun whether commands are only recorded
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "the compact constructor stores an unmodifiable copy")
  public record ProfileBody(String id, String type, Map<String, Object> settings, boolean dryRun) {

    /** Keeps an immutable copy of the settings. */
    public ProfileBody {
      // Map.copyOf rejects null values, and a JSON setting may be null
      settings =
          settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }
  }

  /**
   * A binding as sent and shown.
   *
   * @param id the binding id; generated when missing
   * @param profileId the profile to run
   * @param scope {@code global}, {@code session:<id>}, {@code workflow:<id>} or {@code
   *     workflow:<sessionId>/<workflowId>}
   * @param enabled whether it runs; true when missing
   */
  public record BindingBody(String id, String profileId, String scope, Boolean enabled) {}

  /**
   * The supervisor types that profiles may use.
   *
   * @return the types
   */
  @GetMapping("/types")
  @Operation(summary = "List supervisor types")
  public Mono<ApiResponse<List<String>>> types() {
    return Mono.fromSupplier(() -> ApiResponse.success(200, "Types retrieved", service.types()));
  }

  /**
   * List the profiles.
   *
   * @return the profiles, secrets hidden
   */
  @GetMapping("/profiles")
  @Operation(summary = "List supervisor profiles")
  public Mono<ApiResponse<List<ProfileBody>>> profiles() {
    return service
        .profiles()
        .map(SupervisorBindingsController::view)
        .collectList()
        .map(list -> ApiResponse.success(200, "Profiles retrieved", list));
  }

  /**
   * Create a profile.
   *
   * @param body the profile
   * @return the profile; 409 if the id exists, 400 for an unknown type or invalid settings
   */
  @PostMapping("/profiles")
  @Operation(summary = "Create a supervisor profile")
  public Mono<ApiResponse<ProfileBody>> createProfile(@RequestBody final ProfileBody body) {
    return service
        .profiles()
        .filter(existing -> existing.id().equals(body.id()))
        .hasElements()
        .flatMap(
            exists ->
                exists
                    ? Mono.error(new ConflictException("Profile exists: " + body.id()))
                    : service.saveProfile(profile(body.id(), body)))
        .map(saved -> ApiResponse.success(201, "Profile created", view(saved)));
  }

  /**
   * Change a profile; bindings that use it restart with the new settings.
   *
   * @param id the profile id
   * @param body the new profile; its id is ignored
   * @return the profile
   */
  @PutMapping("/profiles/{id}")
  @Operation(summary = "Replace a supervisor profile")
  public Mono<ApiResponse<ProfileBody>> replaceProfile(
      @PathVariable final String id, @RequestBody final ProfileBody body) {
    return service
        .saveProfile(profile(id, body))
        .map(saved -> ApiResponse.success(200, "Profile saved", view(saved)));
  }

  /**
   * Delete a profile that no binding uses.
   *
   * @param id the profile id
   * @return nothing; 404 if unknown, 409 if a binding uses it
   */
  @DeleteMapping("/profiles/{id}")
  @Operation(summary = "Delete a supervisor profile")
  public Mono<ApiResponse<Void>> deleteProfile(@PathVariable final String id) {
    return service
        .deleteProfile(id)
        .then(Mono.fromSupplier(() -> ApiResponse.success(200, "Profile deleted", null)));
  }

  /**
   * List the bindings.
   *
   * @return the bindings
   */
  @GetMapping("/bindings")
  @Operation(summary = "List supervisor bindings")
  public Mono<ApiResponse<List<BindingBody>>> bindings() {
    return service
        .bindings()
        .map(SupervisorBindingsController::view)
        .collectList()
        .map(list -> ApiResponse.success(200, "Bindings retrieved", list));
  }

  /**
   * Attach a profile to a scope. An enabled binding starts at once; it supervises the runs of its
   * scope from now on, including runs already going.
   *
   * @param body the binding
   * @return the binding; 404 for an unknown profile, 400 for a bad scope, 409 at the limit
   */
  @PostMapping("/bindings")
  @Operation(summary = "Attach a supervisor")
  public Mono<ApiResponse<BindingBody>> attach(@RequestBody final BindingBody body) {
    return Mono.fromSupplier(
            () ->
                new SupervisorBinding(
                    body.id() == null || body.id().isBlank()
                        ? UUID.randomUUID().toString()
                        : body.id(),
                    body.profileId(),
                    SupervisorScope.parse(body.scope()),
                    body.enabled() == null || body.enabled()))
        .flatMap(service::attach)
        .map(saved -> ApiResponse.success(201, "Supervisor attached", view(saved)));
  }

  /**
   * The newest decisions about the commands supervisors emitted since startup.
   *
   * @param binding only this supervisor or binding id; all when missing
   * @param limit most entries, 1 to 500; 50 by default
   * @return the decisions, newest first
   */
  @GetMapping("/decisions")
  @Operation(summary = "Recent supervisor decisions")
  public Mono<ApiResponse<List<SupervisorDecision>>> decisions(
      @RequestParam(required = false) final String binding,
      @RequestParam(defaultValue = "50") final int limit) {
    return Mono.fromSupplier(
        () ->
            ApiResponse.success(
                200,
                "Decisions retrieved",
                host.decisions(binding, Math.min(MAX_DECISIONS, Math.max(1, limit)))));
  }

  /**
   * Detach a binding; its supervisor stops without a restart.
   *
   * @param id the binding id
   * @return nothing; 404 if unknown
   */
  @DeleteMapping("/bindings/{id}")
  @Operation(summary = "Detach a supervisor")
  public Mono<ApiResponse<Void>> detach(@PathVariable final String id) {
    return service
        .detach(id)
        .then(Mono.fromSupplier(() -> ApiResponse.success(200, "Supervisor detached", null)));
  }

  private static SupervisorProfile profile(final String id, final ProfileBody body) {
    return new SupervisorProfile(id, body.type(), body.settings(), body.dryRun());
  }

  private static ProfileBody view(final SupervisorProfile profile) {
    return new ProfileBody(
        profile.id(), profile.type(), hide(profile.settings()), profile.dryRun());
  }

  private static BindingBody view(final SupervisorBinding binding) {
    return new BindingBody(
        binding.id(), binding.profileId(), binding.scope().key(), binding.enabled());
  }

  /** Replaces the values under secret-looking keys, at any depth. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> hide(final Map<String, Object> settings) {
    final Map<String, Object> out = new ConcurrentHashMap<>();
    settings.forEach(
        (key, value) -> {
          if (value == null) {
            return;
          }
          if (SECRET.matcher(key).matches()) {
            out.put(key, HIDDEN);
          } else if (value instanceof Map<?, ?> nested) {
            out.put(key, hide((Map<String, Object>) nested));
          } else {
            out.put(key, value);
          }
        });
    return out;
  }
}
