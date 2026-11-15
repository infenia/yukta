// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.NotificationPreferencesRequest;
import com.infenia.yukta.dto.request.NotificationReadRequest;
import com.infenia.yukta.dto.response.InputRequestView;
import com.infenia.yukta.dto.response.NotificationEvent;
import com.infenia.yukta.dto.response.NotificationSummary;
import com.infenia.yukta.dto.response.NotificationView;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.notify.NotificationRuleSettings;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.input.InputRequestQueries;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.NotLeaderException;
import com.infenia.yukta.service.notify.NotificationChange;
import com.infenia.yukta.service.notify.NotificationPreferences;
import com.infenia.yukta.service.notify.NotificationQuery;
import com.infenia.yukta.service.notify.NotificationService;
import com.infenia.yukta.service.notify.Receipt;
import com.infenia.yukta.service.notify.Recipients;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The inbox of the caller: what Yukta tells them and what it needs from them. Every endpoint and
 * the stream show only what the caller may see: the session must be reachable by the key and an
 * audience selector must match ({@link Recipients}). Seeing an item never allows answering it:
 * answers stay on {@code POST /api/executions/{id}/inputs/{id}/answer}, which has its own rules.
 *
 * <p>Titles and bodies can come from a workflow or an AI agent: show them as untrusted text. On a
 * standby server every call is a 503, like the input endpoints.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications API", description = "The caller's inbox of alerts and questions")
@RegisterReflectionForBinding({
  NotificationView.class,
  NotificationSummary.class,
  NotificationEvent.class,
  NotificationPreferencesRequest.class,
  NotificationReadRequest.class,
  InputRequestView.class
})
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects", "PMD.ExcessiveImports"})
public class NotificationsController {

  /** Most changes replayed after a reconnect; beyond it the client loads the list again. */
  public static final int MAX_REPLAY = 500;

  /** Most streams one key may have open at once. */
  public static final int MAX_STREAMS_PER_KEY = 8;

  /** Largest page size. */
  private static final int MAX_SIZE = 200;

  private static final String RESET_EVENT = "reset";

  /** Writes and reads the inbox. */
  private final NotificationService service;

  /** Reads the input request an action points at. */
  private final InputRequestQueries inputs;

  /** Tells whether this server is the leader. */
  private final LeaderElection leader;

  /** The changes of every stream, each with its input request read once; built on first use. */
  private final AtomicReference<Flux<Shared>> sharedChanges = new AtomicReference<>();

  /** The caller's items, newest first. */
  @GetMapping
  @Operation(
      summary = "List my notifications",
      description =
          "The notifications the caller may see, newest change first, without those the caller"
              + " dismissed. Each has read and dismissed and, for an action, the input request, so"
              + " it can be answered without a second call. severity is a minimum. The total is in"
              + " X-Total-Count. 503 on a standby server.")
  public Mono<ResponseEntity<ApiResponse<List<NotificationView>>>> list(
      @Parameter(description = "ACTIVE or RESOLVED") @RequestParam(required = false)
          final Notification.State state,
      @Parameter(description = "INFO or ACTION") @RequestParam(required = false)
          final NotificationKind kind,
      @Parameter(description = "This severity or above: INFO, WARN or ERROR")
          @RequestParam(required = false)
          final NotificationSeverity severity,
      @Parameter(description = "Only notifications the caller has not read")
          @RequestParam(required = false)
          final Boolean unread,
      @Parameter(description = "Only this session") @RequestParam(required = false)
          final String sessionId,
      @Parameter(description = "Page, from 0") @RequestParam(defaultValue = "0") final int page,
      @Parameter(description = "Page size (default 50, at most 200)")
          @RequestParam(defaultValue = "50")
          final int size) {
    final int pageSize = Math.clamp(size, 1, MAX_SIZE);
    final int from = Math.max(page, 0) * pageSize;
    return caller()
        .flatMap(me ->
            mine(me, new NotificationQuery(state, kind, sessionId, 0))
                .filter(item ->
                    (severity == null || item.notification().severity().compareTo(severity) >= 0)
                        && (!Boolean.TRUE.equals(unread) || !item.read()))
                .collectList()
                .flatMap(all ->
                    Flux.fromIterable(all.stream().skip(from).limit(pageSize).toList())
                        .concatMap(this::view)
                        .collectList()
                        .map(items ->
                            ResponseEntity.ok()
                                .header("X-Total-Count", String.valueOf(all.size()))
                                .body(
                                    ApiResponse.success(200, "Notifications retrieved", items)))));
  }

  /** What the bell shows. */
  @GetMapping("/summary")
  @Operation(
      summary = "Summarize my notifications",
      description =
          "Active actions for the caller, unread info notifications and the highest severity of"
              + " those. 503 on a standby server.")
  public Mono<ApiResponse<NotificationSummary>> summary() {
    return caller()
        .flatMap(me ->
            mine(me, NotificationQuery.ALL)
                .collectList()
                .map(items -> {
                  final List<Item> actions =
                      items.stream()
                          .filter(i ->
                              i.notification().kind() == NotificationKind.ACTION
                                  && i.notification().state() == Notification.State.ACTIVE)
                          .toList();
                  final List<Item> unread =
                      items.stream()
                          .filter(
                              i -> i.notification().kind() == NotificationKind.INFO && !i.read())
                          .toList();
                  final NotificationSeverity highest =
                      java.util.stream.Stream.concat(actions.stream(), unread.stream())
                          .map(i -> i.notification().severity())
                          .max(Comparator.naturalOrder())
                          .orElse(null);
                  return ApiResponse.success(
                      200,
                      "Summary retrieved",
                      new NotificationSummary(actions.size(), unread.size(), highest));
                }));
  }

  /** Marks notifications read. */
  @PostMapping("/read")
  @Operation(
      summary = "Mark notifications read",
      description =
          "Marks the listed ids, or with all=true everything the caller sees (up to upToSeq when"
              + " given), as read for the caller. An id the caller cannot see is ignored."
              + " Returns how many were marked.")
  public Mono<ApiResponse<Map<String, Integer>>> read(
      @RequestBody final NotificationReadRequest body) {
    final List<String> listed = body.ids();
    final Long cap = body.upToSeq();
    final Set<String> wanted = listed == null ? Set.of() : new HashSet<>(listed);
    final boolean all = Boolean.TRUE.equals(body.all());
    final long upTo = cap == null ? Long.MAX_VALUE : cap;
    return caller()
        .flatMap(me ->
            mine(me, NotificationQuery.ALL)
                .filter(item ->
                    all
                        ? !item.read() && item.notification().seq() <= upTo
                        : wanted.contains(item.notification().id()))
                .map(item -> item.notification().id())
                .collectList()
                .flatMap(ids ->
                    service
                        .markRead(me.recipient(), ids)
                        .thenReturn(
                            ApiResponse.success(
                                200, "Marked read", Map.of("marked", ids.size())))));
  }

  /** Hides a notification for the caller. */
  @PostMapping("/{id}/dismiss")
  @Operation(
      summary = "Dismiss a notification",
      description =
          "Hides an info notification or a resolved action for the caller. An action that is still"
              + " open cannot be dismissed (409); it goes when its request ends. 404 for an id"
              + " the caller cannot see.")
  public Mono<ApiResponse<Void>> dismiss(
      @Parameter(description = "Notification id") @PathVariable final String id) {
    return caller()
        .flatMap(me ->
            mine(me, NotificationQuery.ALL)
                .filter(item -> item.notification().id().equals(id))
                .next()
                .switchIfEmpty(Mono.error(new NotFoundException("No notification " + id)))
                .then(service.dismiss(me.recipient(), id))
                .thenReturn(ApiResponse.<Void>success(200, "Notification dismissed", null)));
  }

  /** The caller's preferences. */
  @GetMapping("/preferences")
  @Operation(summary = "Read my notification preferences")
  public Mono<ApiResponse<NotificationPreferencesRequest>> preferences() {
    return caller()
        .flatMap(me ->
            service
                .preferences(me.recipient())
                .map(p ->
                    ApiResponse.success(
                        200,
                        "Preferences retrieved",
                        new NotificationPreferencesRequest(p.levels(), p.mutedSessions()))));
  }

  /** Replaces the caller's preferences. */
  @PutMapping("/preferences")
  @Operation(
      summary = "Set my notification preferences",
      description =
          "Per category OFF, INBOX or DESKTOP, and muted sessions. The category input cannot be"
              + " OFF, and a category must be a known one (400).")
  public Mono<ApiResponse<NotificationPreferencesRequest>> savePreferences(
      @RequestBody final NotificationPreferencesRequest body) {
    return caller()
        .flatMap(me -> {
          body.levels().keySet().forEach(NotificationsController::checkCategory);
          final NotificationPreferences saved =
              new NotificationPreferences(me.recipient(), body.levels(), body.mutedSessions());
          return service
              .savePreferences(saved)
              .thenReturn(ApiResponse.success(200, "Preferences saved", body));
        });
  }

  private static void checkCategory(final String category) {
    if (!NotificationRuleSettings.CATEGORIES.contains(category)) {
      throw new IllegalArgumentException(
          "Unknown category '"
              + category
              + "'; use one of "
              + NotificationRuleSettings.CATEGORIES.stream().sorted().toList());
    }
  }

  /**
   * The caller's changes as they happen.
   *
   * @param lastEventId the last event id the client saw, {@code <epoch>-<seq>}
   * @return events whose data carries type created, updated, resolved, receipt or reset
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream my notifications",
      description =
          "created, updated, resolved and receipt events for the caller (the type is in the data;"
              + " the events have no SSE name, so a browser EventSource delivers them to"
              + " onmessage), with ids <epoch>-<seq>. A reconnect with Last-Event-ID replays the"
              + " changes after it (at most 500); a position of another epoch (the server"
              + " restarted) or too far back gets one reset event, and the client loads the list"
              + " again. At most 8 streams per key; 503 on a standby server.")
  public Flux<ServerSentEvent<NotificationEvent>> stream(
      @RequestHeader(value = "Last-Event-ID", required = false)
          final @Nullable String lastEventId) {
    return caller()
        .zipWith(service.epoch())
        .flatMapMany(pair -> {
          final Me me = pair.getT1();
          final String epoch = pair.getT2();
          // ponytail: a change that moves an item out of my audience (an escalation to another
          // tier) is not sent to me; the client drops it when it loads the list again.
          final Sinks.Many<ServerSentEvent<NotificationEvent>> live =
              Sinks.many().unicast().onBackpressureBuffer();
          final AtomicLong sent = new AtomicLong(-1);
          final Disposable feed =
              shared()
                  .concatMap(shared -> event(me, epoch, shared))
                  .filter(sse -> after(sse, sent))
                  .subscribe(live::tryEmitNext);
          return SseStreams.named(
              Flux.concat(replay(me, epoch, lastEventId, sent), live.asFlux())
                  .doFinally(_ -> feed.dispose()),
              SseStreams.HEARTBEAT,
              MAX_STREAMS_PER_KEY);
        });
  }

  /** Drops a live event that the replay already sent. */
  /* default */ static boolean after(
      final ServerSentEvent<NotificationEvent> sse, final AtomicLong sent) {
    final String id = sse.id();
    if (id == null) {
      return true;
    }
    final long seq = Long.parseLong(id.substring(id.lastIndexOf('-') + 1));
    return seq > sent.get();
  }

  private Flux<ServerSentEvent<NotificationEvent>> replay(
      final Me me, final String epoch, final @Nullable String lastEventId, final AtomicLong sent) {
    if (lastEventId == null || lastEventId.isBlank()) {
      return Flux.empty();
    }
    final int dash = lastEventId.lastIndexOf('-');
    long since = -1;
    if (dash > 0) {
      try {
        since = Long.parseLong(lastEventId.substring(dash + 1).strip());
      } catch (NumberFormatException bad) {
        since = -1;
      }
    }
    if (since < 0 || !epoch.equals(lastEventId.substring(0, dash))) {
      return Flux.just(reset());
    }
    final long from = since;
    return mine(me, NotificationQuery.since(from))
        .collectList()
        .flatMapMany(items -> {
          if (items.size() > MAX_REPLAY) {
            return Flux.just(reset());
          }
          final List<Item> ascending =
              items.stream().sorted(Comparator.comparingLong(i -> i.notification().seq())).toList();
          ascending.stream().mapToLong(i -> i.notification().seq()).max().ifPresent(sent::set);
          return Flux.fromIterable(ascending)
              .concatMap(item ->
                  view(item)
                      .map(v ->
                          sse(epoch, replayType(item.notification()), v, item.notification())));
        });
  }

  private static String replayType(final Notification n) {
    if (n.state() == Notification.State.RESOLVED) {
      return "resolved";
    }
    return n.createdAt().equals(n.updatedAt()) ? "created" : "updated";
  }

  private ServerSentEvent<NotificationEvent> reset() {
    return ServerSentEvent.<NotificationEvent>builder()
        .data(new NotificationEvent(RESET_EVENT, null, null, null, null))
        .build();
  }

  private static ServerSentEvent<NotificationEvent> sse(
      final String epoch, final String type, final NotificationView view, final Notification n) {
    return ServerSentEvent.<NotificationEvent>builder()
        .id(epoch + "-" + n.seq())
        .data(new NotificationEvent(type, view, null, null, null))
        .build();
  }

  /** The stream event of a change, or nothing when the caller may not see it. */
  private Flux<ServerSentEvent<NotificationEvent>> event(
      final Me me, final String epoch, final Shared shared) {
    final NotificationChange change = shared.change();
    final Notification n = change.notification();
    final Receipt receipt = change.receipt();
    if (change.kind() == NotificationChange.Kind.RECEIPT) {
      return Objects.requireNonNull(receipt).recipient().equals(me.recipient())
          ? Flux.just(
              ServerSentEvent.<NotificationEvent>builder()
                  .data(
                      new NotificationEvent(
                          "receipt",
                          null,
                          receipt.notificationId(),
                          receipt.readAt(),
                          receipt.dismissedAt()))
                  .build())
          : Flux.empty();
    }
    if (!Recipients.sees(Objects.requireNonNull(n), me.match())) {
      return Flux.empty();
    }
    final String type = change.kind().name().toLowerCase(java.util.Locale.ROOT);
    return service
        .receipts(me.recipient(), List.of(n.id()))
        .next()
        .map(r -> new Item(n, true, r.dismissedAt() != null))
        .defaultIfEmpty(new Item(n, false, false))
        .flatMap(item -> build(item, Mono.justOrEmpty(shared.input())))
        .map(v -> sse(epoch, type, v, n))
        .flux();
  }

  // ---- helpers ----

  /** The caller, from the request; a 503 on a standby. */
  private Mono<Me> caller() {
    return Mono.deferContextual(ctx -> {
      if (!leader.isLeader()) {
        return Mono.error(new NotLeaderException());
      }
      final ApiKeys.Match match = ctx.getOrDefault(CallerIdentity.CONTEXT_KEY, null);
      return Mono.just(new Me(match, Recipients.recipient(match)));
    });
  }

  /** The caller and the name their inbox is kept under. */
  private record Me(ApiKeys.@Nullable Match match, String recipient) {}

  /** A visible notification with what the caller did with it. */
  private record Item(Notification notification, boolean read, boolean dismissed) {}

  /** What the caller sees for a query, without what they dismissed, newest change first. */
  private Flux<Item> mine(final Me me, final NotificationQuery query) {
    return service
        .visible(query, me.match())
        .collectList()
        .flatMapMany(visible -> {
          final Map<String, Receipt> receipts = new ConcurrentHashMap<>();
          return service
              .receipts(me.recipient(), visible.stream().map(Notification::id).toList())
              .doOnNext(r -> receipts.put(r.notificationId(), r))
              .thenMany(
                  Flux.fromIterable(visible)
                      .map(n -> {
                        final Receipt r = receipts.get(n.id());
                        return new Item(n, r != null, r != null && r.dismissedAt() != null);
                      })
                      .filter(item -> !item.dismissed()));
        });
  }

  private Mono<NotificationView> view(final Item item) {
    return build(item, inputOf(item.notification()));
  }

  private static Mono<NotificationView> build(final Item item, final Mono<InputRequestView> input) {
    final Notification n = item.notification();
    return input
        .map(v -> NotificationView.from(n, item.read(), item.dismissed(), v))
        .switchIfEmpty(
            Mono.fromSupplier(() -> NotificationView.from(n, item.read(), item.dismissed(), null)));
  }

  /** The input request an action points at, with its answer; empty for an alert or a gone one. */
  private Mono<InputRequestView> inputOf(final @Nullable Notification n) {
    if (n == null || n.kind() != NotificationKind.ACTION) {
      return Mono.empty();
    }
    return inputs
        .require(
            Objects.requireNonNull(n.executionId()), Objects.requireNonNull(n.inputRequestId()))
        .flatMap(request ->
            inputs
                .answer(request)
                .map(response -> InputRequestView.from(request, response))
                .switchIfEmpty(Mono.fromSupplier(() -> InputRequestView.from(request, null))))
        .onErrorResume(NotFoundException.class, _ -> Mono.empty());
  }

  /** A change with the input request it points at, read once for every stream. */
  private record Shared(NotificationChange change, @Nullable InputRequestView input) {}

  /**
   * Every change once, with its input request. Each stream used to read and redact the request
   * itself, so 200 streams did that 200 times per change; now they share one read.
   */
  private Flux<Shared> shared() {
    return sharedChanges.updateAndGet(current ->
        current != null
            ? current
            : service
                .changes()
                .concatMap(change ->
                    inputOf(change.notification())
                        .map(input -> new Shared(change, input))
                        .defaultIfEmpty(new Shared(change, null)))
                .share());
  }
}
