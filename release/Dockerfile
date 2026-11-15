# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Runtime image for a PREBUILT native server binary. The binary comes from release/build-server.sh
# (or a CI artifact), so the image and the downloadable archive contain the same executable.
# The all-in-one Dockerfile at the repository root builds from source instead.
#
# Build one architecture (linux-amd64 and linux-arm64 are published; see PLATFORMS in lib.sh):
#   docker buildx build --platform linux/amd64 -f release/Dockerfile -t yukta:test .
# The build context is the dist/ directory layout: dist/linux-<arch>/yukta
#
# Same pinned base image as the root Dockerfile: the distroless debug variant, which adds BusyBox so
# PROCESS_EXECUTOR workflows and the examples can run sh and echo.
# Same pinned base image as the root Dockerfile. Check that it is a multi-architecture index:
#   docker buildx imagetools inspect <image>@<digest>   (must list linux/amd64 and linux/arm64)
FROM gcr.io/distroless/cc-debian12:debug-nonroot@sha256:923320b891f20d5f4bd43ed3a72eeee2f3323d481d6f4bd8d0b2c96d1c0758bc

ARG TARGETARCH
ARG VERSION=dev
ARG REVISION=unknown

LABEL org.opencontainers.image.title="Yukta" \
      org.opencontainers.image.description="Reactive DAG-based workflow orchestration server" \
      org.opencontainers.image.source="https://github.com/infenia/yukta" \
      org.opencontainers.image.url="https://yukta.infenia.com/" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.vendor="Infenia Private Limited" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

COPY dist/linux-${TARGETARCH}/yukta /app/yukta

EXPOSE 9858

# Inside a container the app must listen on all interfaces; publish the port deliberately.
# The server refuses to start in this mode without an API key: run with -e YUKTA_API_KEYS_0_NAME=admin -e YUKTA_API_KEYS_0_KEY=<key> -e YUKTA_API_KEYS_0_ROLE=ADMIN.
ENV YUKTA_SERVER_ADDRESS=0.0.0.0

USER nonroot
ENTRYPOINT ["/app/yukta"]
