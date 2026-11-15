// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.MarkedRead;
import com.infenia.yukta.mcp.dto.NotificationList;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.NotLeaderException;
import com.infenia.yukta.service.notify.NotificationQuery;
import com.infenia.yukta.service.notify.NotificationService;
import com.infenia.yukta.service.notify.Receipt;
import com.infenia.yukta.service.notify.Recipients;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Lists and acknowledges the inbox of the caller for the MCP tools. What a caller sees is decided
 * by {@link Recipients}, the same as over REST: the session must be reachable by the key and an
 * audience selector must match. Seeing a question never allows answering it: {@code answer_input}
 * has its own rules.
 */
@Component
public class NotificationProvider {

  /** Most notifications one call lists. */
  public static final int MAX_NOTIFICATIONS = 50;

  /** Longest body one entry carries, in characters. */
  public static final int MAX_BODY = 1000;

  private final NotificationService service;
  private final LeaderElection leader;

  /**
   * Creates the provider.
   *
   * @param service reads and acknowledges the inbox
   * @param leader tells whether this server is the leader
   */
  public NotificationProvider(final NotificationService service, final LeaderElection leader) {
    this.service = service;
    this.leader = leader;
  }

  /**
   * Lists the caller's notifications, newest change first, without those it dismissed.
   *
   * @param unread true for only those the caller has not read
   * @param kind INFO or ACTION, or null for both
   * @return the notifications, at most {@link #MAX_NOTIFICATIONS}
   */
  public Mono<NotificationList> list(final @Nullable Boolean unread, final @Nullable String kind) {
    return caller()
        .flatMap(me -> {
          final NotificationKind wanted = kind == null || kind.isBlank() ? null : kind(kind);
          return mine(me, new NotificationQuery(null, wanted, null, 0))
              .map(items ->
                  items.stream().filter(i -> !Boolean.TRUE.equals(unread) || !i.read()).toList())
              .map(items ->
                  new NotificationList(
                      items.stream()
                          .limit(MAX_NOTIFICATIONS)
                          .map(NotificationProvider::entry)
                          .toList(),
                      items.size() > MAX_NOTIFICATIONS));
        });
  }

  /**
   * Marks notifications read for the caller.
   *
   * @param ids the notification ids, or null or empty with {@code all}
   * @param all true to mark everything the caller sees
   * @return how many were marked
   */
  public Mono<MarkedRead> markRead(final @Nullable List<String> ids, final boolean all) {
    return caller()
        .flatMap(me -> {
          if (!all && (ids == null || ids.isEmpty())) {
            return Mono.error(new IllegalArgumentException("Give ids, or all=true."));
          }
          final Set<String> wanted = ids == null ? Set.of() : new HashSet<>(ids);
          return mine(me, NotificationQuery.ALL)
              .map(items ->
                  items.stream()
                      .filter(i -> all ? !i.read() : wanted.contains(i.notification().id()))
                      .map(i -> i.notification().id())
                      .toList())
              .flatMap(marked ->
                  service
                      .markRead(me.recipient(), marked)
                      .thenReturn(new MarkedRead(marked.size())));
        });
  }

  private static NotificationKind kind(final String kind) {
    try {
      return NotificationKind.valueOf(kind.strip().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("kind must be INFO or ACTION.", unknown);
    }
  }

  private Mono<Me> caller() {
    return Mono.deferContextual(ctx -> {
      if (!leader.isLeader()) {
        return Mono.error(new NotLeaderException());
      }
      final ApiKeys.Match match = ctx.getOrDefault(CallerIdentity.CONTEXT_KEY, null);
      return Mono.just(new Me(match, Recipients.recipient(match)));
    });
  }

  private record Me(ApiKeys.@Nullable Match match, String recipient) {}

  private record Item(Notification notification, boolean read) {}

  /** What the caller sees for a query, without what it dismissed, newest change first. */
  private Mono<List<Item>> mine(final Me me, final NotificationQuery query) {
    return service
        .visible(query, me.match())
        .collectList()
        .flatMap(visible -> {
          final Map<String, Receipt> receipts = new ConcurrentHashMap<>();
          return service
              .receipts(me.recipient(), visible.stream().map(Notification::id).toList())
              .doOnNext(r -> receipts.put(r.notificationId(), r))
              .then(
                  Mono.fromSupplier(() ->
                      visible.stream()
                          .filter(n -> {
                            final Receipt r = receipts.get(n.id());
                            return r == null || r.dismissedAt() == null;
                          })
                          .map(n -> new Item(n, receipts.containsKey(n.id())))
                          .toList()));
        });
  }

  private static String cut(final String body) {
    return body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY) + "…";
  }

  private static NotificationList.Entry entry(final Item item) {
    final Notification n = item.notification();
    final String body = n.body();
    return new NotificationList.Entry(
        n.id(),
        n.kind().name(),
        n.severity().name(),
        n.category(),
        n.title(),
        body == null ? null : cut(body),
        n.sessionId(),
        n.workflowId(),
        n.executionId(),
        n.inputRequestId(),
        n.nodeId(),
        n.state().name(),
        n.resolution(),
        item.read(),
        n.createdAt());
  }
}
