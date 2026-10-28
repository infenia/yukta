// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.InetNameResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
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

  @Test
  void nameResolvingToMetadataIsRefusedForBothLookups() throws Exception {
    final InetAddress metadata =
        InetAddress.getByAddress(
            "metadata.test", new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254});
    final NioEventLoopGroup loop = new NioEventLoopGroup(1);
    try (AddressResolver<InetSocketAddress> resolver =
        new MetadataGuardResolver(executor -> answering(executor, metadata))
            .getResolver(loop.next())) {
      final InetSocketAddress name = InetSocketAddress.createUnresolved("metadata.test", 80);
      final Future<InetSocketAddress> one = resolver.resolve(name);
      final Future<List<InetSocketAddress>> all = resolver.resolveAll(name);

      assertThat(one.awaitUninterruptibly().cause())
          .isInstanceOf(UnknownHostException.class)
          .hasMessageContaining("refused");
      assertThat(all.awaitUninterruptibly().cause()).isInstanceOf(UnknownHostException.class);
    } finally {
      loop.shutdownGracefully().syncUninterruptibly();
    }
  }

  @Test
  void literalMetadataAddressIsLeftToTheHostAllowList() throws Exception {
    final InetAddress metadata =
        InetAddress.getByAddress(new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254});
    final NioEventLoopGroup loop = new NioEventLoopGroup(1);
    try (AddressResolver<InetSocketAddress> resolver =
        new MetadataGuardResolver(executor -> answering(executor, metadata))
            .getResolver(loop.next())) {
      final InetSocketAddress literal =
          InetSocketAddress.createUnresolved(metadata.getHostAddress(), 80);

      assertThat(await(resolver.resolve(literal)).getAddress()).isEqualTo(metadata);
    } finally {
      loop.shutdownGracefully().syncUninterruptibly();
    }
  }

  /** A name lookup that answers every name with the given address. */
  private static InetNameResolver answering(
      final EventExecutor executor, final InetAddress answer) {
    return new InetNameResolver(executor) {
      @Override
      protected void doResolve(final String host, final Promise<InetAddress> promise) {
        promise.setSuccess(answer);
      }

      @Override
      protected void doResolveAll(final String host, final Promise<List<InetAddress>> promise) {
        promise.setSuccess(List.of(answer));
      }
    };
  }

  private static <T> T await(final Future<T> future) {
    future.syncUninterruptibly();
    return future.getNow();
  }

  private static boolean isLoopback(final InetSocketAddress address) {
    return address.getAddress().isLoopbackAddress();
  }
}
