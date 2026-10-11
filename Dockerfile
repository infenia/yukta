// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

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
FROM ghcr.io/graalvm/native-image:latest@sha256:4aeee052a80237fa8b32c074d2b7b7adc92271fc4bda724f0b1c2ea0f2f884cb AS builder

# Install xargs, findutils, and Java 21 for build-logic toolchain requirements
RUN microdnf install -y util-linux findutils java-21-openjdk-devel && microdnf clean all

WORKDIR /build
COPY . .

# Build native image with aggressive optimizations
RUN ./gradlew nativeCompile -x test --no-daemon

# Stage 2: Copy binary (UPX compression attempted but not critical for image size)
FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8 AS compressor

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
ENV YUKTA_SERVER_ADDRESS=0.0.0.0 \
    HOME=/home/nonroot \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

ENTRYPOINT ["/app/yukta"]
