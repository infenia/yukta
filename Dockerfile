# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Copyright 2026 Infenia Private Limited
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Stage 1: Build native image with BellSoft Liberica Native Image Kit (NIK)
FROM bellsoft/liberica-native-image-kit-container:jdk-25-nik-25.0.4.1-glibc@sha256:fd922a6251c8f3c55fe8cae2c6a1684a5325175635337cdf0e811e7b1e059a30 AS builder

# The base image ships Liberica NIK 25 (JDK 25), built from GraalVM Community Edition (GPLv2 with the
# Classpath Exception, so the binaries can be redistributed under Apache-2.0 and AGPL-3.0; matches the
# build-logic toolchain). It is Alpaquita Linux (glibc variant, not musl: the Gradle build downloads a
# glibc Node.js, and the binary runs on the glibc distroless image below). xargs (findutils) is needed by
# the Gradle wrapper and libz.a by the static link, so make sure both are there.
RUN apk add --no-cache bash findutils zlib-static

WORKDIR /build

# Compile the Gradle build logic (Kotlin convention plugins) in its own layer: it depends only on
# the wrapper, the version catalog and build-logic/, so a source change does not redo it.
COPY gradlew ./
COPY gradle gradle
COPY build-logic build-logic
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -p build-logic jar

COPY . .

# Builder heap for native-image; lower it on machines with less memory.
ARG NATIVE_HEAP=16g
# Any non-empty value builds with -Ob: much faster to compile, slower at run time. For rehearsals
# and smoke tests only; never for a release.
ARG NATIVE_QUICK=
# Extra Gradle properties for the flavour of the build. The Plus flavour (AGPL-3.0-only, see
# LICENSING.md) is "-Pyukta.persistence=postgres -Pyukta.workers=true"; empty is the Community build.
ARG YUKTA_BUILD_FLAGS=

# Keep the Gradle home and the pnpm store between builds (BuildKit cache mounts), so dependencies
# download once on a machine. They live outside the image: nothing in the result depends on them.
ENV npm_config_store_dir=/pnpm-store

# Build the native image
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/pnpm-store \
    ./gradlew nativeCompile -x test --no-daemon --max-workers="$(nproc)" \
      -Pnative.heap=${NATIVE_HEAP} -Pnative.static ${NATIVE_QUICK:+-Pnative.quick} \
      ${YUKTA_BUILD_FLAGS}

# Stage 2: Copy binary (UPX compression attempted but not critical for image size)
FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS compressor

RUN apk add --no-cache upx || echo "UPX not available, skipping compression"

COPY --from=builder /build/boot/build/native/nativeCompile/yukta /tmp/yukta

# Attempt UPX compression with fallback (some binaries cannot be compressed)
RUN upx --best --lzma -o /tmp/yukta.compressed /tmp/yukta 2>/dev/null || cp /tmp/yukta /tmp/yukta.compressed

# Stage 3: Final production image with distroless base (minimal footprint)
FROM gcr.io/distroless/cc-debian12:debug-nonroot@sha256:923320b891f20d5f4bd43ed3a72eeee2f3323d481d6f4bd8d0b2c96d1c0758bc

# Distroless debug image: libc6 + dynamic loader + BusyBox (sh, echo, cat, ...), no package manager.
# The shell is there because workflows run commands (PROCESS_EXECUTOR) and the examples use sh and echo.

COPY --from=compressor /tmp/yukta.compressed /app/yukta

# Application will listen on 9858
EXPOSE 9858

# Nonroot user: runs as unprivileged user (UID 65532)
# Set HOME and PATH for application
# Inside a container the app must listen on all interfaces; publish the port deliberately.
# The server refuses to start in this mode without an API key: run with -e YUKTA_API_KEYS_0_NAME=admin -e YUKTA_API_KEYS_0_KEY=<key> -e YUKTA_API_KEYS_0_ROLE=ADMIN.
ENV YUKTA_SERVER_ADDRESS=0.0.0.0 \
    HOME=/home/nonroot \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/busybox

USER nonroot

ENTRYPOINT ["/app/yukta"]
