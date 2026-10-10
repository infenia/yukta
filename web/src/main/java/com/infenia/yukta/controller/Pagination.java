// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import java.util.List;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import org.springframework.http.ResponseEntity;

/**
 * Cursor pagination for list endpoints: {@code ?limit=&cursor=}, where the cursor is the ID of the
 * last item of the previous page. A further page is announced in the {@code X-Next-Cursor} header.
 */
@UtilityClass
public class Pagination {

  /** Page size when the caller gives none. */
  public static final int DEFAULT_LIMIT = 500;

  /** Largest page a caller may ask for. */
  public static final int MAX_LIMIT = 1_000;

  /** Response header carrying the cursor of the next page. */
  public static final String NEXT_CURSOR = "X-Next-Cursor";

  /** One page of items and the cursor of the next page, if any. */
  public record Slice<T>(List<T> items, String next) {

    /** Compact constructor that copies the items. */
    public Slice {
      items = List.copyOf(items);
    }
  }

  /**
   * Cut one page out of an ordered list.
   *
   * @param all every item, in a stable order
   * @param limit requested page size, or null for the default
   * @param cursor ID of the last item already seen, or null for the first page
   * @param idOf extracts an item's ID
   * @param <T> item type
   * @return the page and, when more items remain, the cursor of the next page
   */
  public static <T> Slice<T> slice(
      final List<T> all, final Integer limit, final String cursor, final Function<T, String> idOf) {
    final int size = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    int from = 0;
    if (cursor != null) {
      for (int i = 0; i < all.size(); i++) {
        if (idOf.apply(all.get(i)).equals(cursor)) {
          from = i + 1;
          break;
        }
      }
    }
    final int end = Math.min(all.size(), from + size);
    final List<T> items = all.subList(from, end);
    return new Slice<>(items, end < all.size() ? idOf.apply(all.get(end - 1)) : null);
  }

  /**
   * Slice a list and wrap the page as a response.
   *
   * @param all every item, in a stable order
   * @param limit requested page size, or null for the default
   * @param cursor ID of the last item already seen, or null for the first page
   * @param idOf extracts an item's ID
   * @param message the success message
   * @param <T> item type
   * @return the page, with the next-cursor header when more items remain
   */
  public static <T> ResponseEntity<ApiResponse<List<T>>> page(
      final List<T> all,
      final Integer limit,
      final String cursor,
      final Function<T, String> idOf,
      final String message) {
    final Slice<T> slice = slice(all, limit, cursor, idOf);
    return respond(slice.items(), slice.next(), message);
  }

  /**
   * Wrap items as a response, announcing the next page when there is one.
   *
   * @param items the items of this page
   * @param next the next cursor, or null
   * @param message the success message
   * @param <T> item type
   * @return the response
   */
  public static <T> ResponseEntity<ApiResponse<List<T>>> respond(
      final List<T> items, final String next, final String message) {
    final ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (next != null) {
      response.header(NEXT_CURSOR, next);
    }
    return response.body(ApiResponse.success(200, message, items));
  }
}
