// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.plugin.util.HostAllowList;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultNameResolver;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Function;

/**
 * Resolves names like the default resolver, but fails a name that resolves to a link-local or cloud
 * metadata address, so a name that passed the host check cannot be rebound to the metadata service
 * by DNS between the check and the connect. Literal addresses are left to {@link HostAllowList},
 * which lets an operator list one by name.
 */
final class MetadataGuardResolver extends AddressResolverGroup<InetSocketAddress> {

  /** The one instance, resolving with the system resolver; the group keeps no state. */
  /* default */ static final MetadataGuardResolver INSTANCE =
      new MetadataGuardResolver(DefaultNameResolver::new);

  /** The name lookup is a parameter so tests can answer a name with a metadata address. */
  private final Function<EventExecutor, InetNameResolver> lookup;

  /* default */ MetadataGuardResolver(final Function<EventExecutor, InetNameResolver> lookup) {
    super();
    this.lookup = lookup;
  }

  @Override
  protected AddressResolver<InetSocketAddress> newResolver(final EventExecutor executor) {
    final InetNameResolver inner = lookup.apply(executor);
    return new InetSocketAddressResolver(
        executor,
        new InetNameResolver(executor) {
          @Override
          protected void doResolve(final String host, final Promise<InetAddress> promise) {
            inner.resolve(host).addListener(done -> settle(host, done, promise));
          }

          @Override
          protected void doResolveAll(final String host, final Promise<List<InetAddress>> promise) {
            inner.resolveAll(host).addListener(done -> settleAll(host, done, promise));
          }
        });
  }

  private static <T> T resultOf(final Future<T> done) {
    return done.getNow();
  }

  private static void settle(
      final String host, final Future<?> done, final Promise<InetAddress> promise) {
    if (!done.isSuccess()) {
      promise.tryFailure(done.cause());
      return;
    }
    final InetAddress address = (InetAddress) resultOf(done);
    if (refused(host, address)) {
      promise.tryFailure(blocked(host, address));
    } else {
      promise.trySuccess(address);
    }
  }

  private static void settleAll(
      final String host, final Future<?> done, final Promise<List<InetAddress>> promise) {
    if (!done.isSuccess()) {
      promise.tryFailure(done.cause());
      return;
    }
    @SuppressWarnings("unchecked")
    final List<InetAddress> all = (List<InetAddress>) resultOf(done);
    // every address: the client may try any of them
    final InetAddress bad = all.stream().filter(a -> refused(host, a)).findFirst().orElse(null);
    if (bad == null) {
      promise.trySuccess(all);
    } else {
      promise.tryFailure(blocked(host, bad));
    }
  }

  private static boolean refused(final String host, final InetAddress address) {
    return !host.equals(address.getHostAddress()) && HostAllowList.isMetadataAddress(address);
  }

  private static UnknownHostException blocked(final String host, final InetAddress address) {
    return new UnknownHostException(
        "Host '"
            + host
            + "' resolved to the link-local or metadata address "
            + address.getHostAddress()
            + ", refused");
  }
}
