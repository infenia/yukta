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

# Stage 1: Build native image with GraalVM
FROM ghcr.io/graalvm/native-image-community:25@sha256:0d936f32bb8acb5bc60c41b33e05f064d7a6aaf36b726538296c54949bd4a3c0 AS builder

# The base image ships GraalVM CE for JDK 25 (matches the build-logic toolchain),
# plus xargs and findutils, which the Gradle wrapper needs.

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

# Keep the Gradle home and the pnpm store between builds (BuildKit cache mounts), so dependencies
# download once on a machine. They live outside the image: nothing in the result depends on them.
ENV npm_config_store_dir=/pnpm-store

# Build native image with aggressive optimizations
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/pnpm-store \
    ./gradlew nativeCompile -x test --no-daemon --max-workers="$(nproc)" \
      -Pnative.heap=${NATIVE_HEAP} -Pnative.static ${NATIVE_QUICK:+-Pnative.quick}

# Stage 2: Copy binary (UPX compression attempted but not critical for image size)
FROM alpine:3.24@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6 AS compressor

RUN apk add --no-cache upx || echo "UPX not available, skipping compression"

COPY --from=builder /build/boot/build/native/nativeCompile/yukta /tmp/yukta

# Attempt UPX compression with fallback (some binaries cannot be compressed)
RUN upx --best --lzma -o /tmp/yukta.compressed /tmp/yukta 2>/dev/null || cp /tmp/yukta /tmp/yukta.compressed

# Stage 3: Final production image with distroless base (minimal footprint)
FROM gcr.io/distroless/cc-debian12:nonroot@sha256:9dac0a79194e45a7da0158a9c6da57b217585af0786db3845d1f0ec1a0dd182f

# Distroless images: no shell, no package manager, no unnecessary files
# Only libc6 + dynamic loader + application binary

COPY --from=compressor /tmp/yukta.compressed /app/yukta

# Application will listen on 8080
EXPOSE 8080

# Nonroot user: runs as unprivileged user (UID 65532)
# Set HOME and PATH for application
# Inside a container the app must listen on all interfaces; publish the port deliberately.
# The server refuses to start in this mode without an API key: run with -e YUKTA_API_KEY=<key>.
ENV YUKTA_SERVER_ADDRESS=0.0.0.0 \
    HOME=/home/nonroot \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

USER nonroot

ENTRYPOINT ["/app/yukta"]
