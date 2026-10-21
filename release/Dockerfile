# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Runtime image for a PREBUILT native server binary. The binary comes from release/build-server.sh
# (or a CI artifact), so the image and the downloadable archive contain the same executable.
# The all-in-one Dockerfile at the repository root builds from source instead.
#
# Build one architecture:
#   docker buildx build --platform linux/arm64 -f release/Dockerfile -t yukta:test .
# The build context is the dist/ directory layout: dist/linux-<arch>/yukta
#
# Same pinned base image as the root Dockerfile. Check that it is a multi-architecture index:
#   docker buildx imagetools inspect <image>@<digest>   (must list linux/amd64 and linux/arm64)
FROM gcr.io/distroless/cc-debian12:nonroot@sha256:9dac0a79194e45a7da0158a9c6da57b217585af0786db3845d1f0ec1a0dd182f

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

EXPOSE 8080

# Inside a container the app must listen on all interfaces; publish the port deliberately.
# The server refuses to start in this mode without an API key: run with -e YUKTA_API_KEY=<key>.
ENV YUKTA_SERVER_ADDRESS=0.0.0.0

USER nonroot
ENTRYPOINT ["/app/yukta"]
