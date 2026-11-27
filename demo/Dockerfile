# Public read-only demo image: the released native server plus the demo session files.
# Pin by digest once you have checked the tag:  FROM ghcr.io/infenia/yukta:0.1.0-alpha.5@sha256:<digest>
FROM ghcr.io/infenia/yukta:0.1.0-alpha.5

COPY --chown=65532:65532 demo/sessions /app/sessions
ENV YUKTA_BOOTSTRAP_DIR=/app/sessions
