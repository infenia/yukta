// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link MetadataGuardResolver}. */
@NoArgsConstructor
class MetadataGuardResolverTest {

  @Test
  void resolveNameForLoopbackAddressStillWorks() throws Exception {
    final NioEventLoopGroup loop = new NioEventLoopGroup(1);
    try (AddressResolver<InetSocketAddress> resolver =
        MetadataGuardResolver.INSTANCE.getResolver(loop.next())) {
      final InetSocketAddress one =
          await(resolver.resolve(InetSocketAddress.createUnresolved("localhost", 80)));
      final List<InetSocketAddress> all =
          await(resolver.resolveAll(InetSocketAddress.createUnresolved("localhost", 80)));

      assertThat(isLoopback(one)).isTrue();
      assertThat(all).isNotEmpty();
    } finally {
      loop.shutdownGracefully().syncUninterruptibly();
    }
  }

  @Test
  void unresolvableNameFailsBothLookups() throws Exception {
    final NioEventLoopGroup loop = new NioEventLoopGroup(1);
    try (AddressResolver<InetSocketAddress> resolver =
        MetadataGuardResolver.INSTANCE.getResolver(loop.next())) {
      final InetSocketAddress unknown =
          InetSocketAddress.createUnresolved("no-such-host.invalid", 80);
      final Future<InetSocketAddress> one = resolver.resolve(unknown);
      final Future<List<InetSocketAddress>> all = resolver.resolveAll(unknown);

      assertThat(one.awaitUninterruptibly().isSuccess()).isFalse();
      assertThat(all.awaitUninterruptibly().isSuccess()).isFalse();
    } finally {
      loop.shutdownGracefully().syncUninterruptibly();
    }
  }

  private static <T> T await(final Future<T> future) {
    future.syncUninterruptibly();
    return future.getNow();
  }

  private static boolean isLoopback(final InetSocketAddress address) {
    return address.getAddress().isLoopbackAddress();
  }
}
