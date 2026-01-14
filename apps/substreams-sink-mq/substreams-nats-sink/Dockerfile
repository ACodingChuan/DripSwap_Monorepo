# Multi-stage build for NATS-specific Substreams Sink
FROM rust:1.89-bookworm AS builder

# Install system dependencies for building
RUN apt-get update && apt-get install -y \
    pkg-config \
    libssl-dev \
    cmake \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Set up minimal workspace build
WORKDIR /workspace

# Create minimal workspace Cargo.toml for just what we need
RUN echo '[workspace]\nresolver = "2"\nmembers = [\n    "substreams-core",\n    "substreams-nats-sink"\n]\n\n[profile.release]\nlto = true\ncodegen-units = 1\npanic = "abort"' > Cargo.toml

# Copy only required workspace members
COPY substreams-core ./substreams-core
COPY substreams-nats-sink ./substreams-nats-sink

# Build the NATS sink application
WORKDIR /workspace/substreams-nats-sink
RUN cargo build --release

# Runtime stage with minimal dependencies
FROM debian:bookworm-slim

# Install runtime dependencies and comprehensive CA certificates
RUN apt-get update && apt-get install -y \
    ca-certificates \
    libssl3 \
    curl \
    wget \
    protobuf-compiler \
    && rm -rf /var/lib/apt/lists/*

# Download latest Mozilla CA bundle to ensure we have current certificates
# This specifically addresses Google Trust Services certificate issues
RUN wget -O /usr/local/share/ca-certificates/mozilla-ca-bundle.crt \
    https://curl.se/ca/cacert.pem \
    && chmod 644 /usr/local/share/ca-certificates/mozilla-ca-bundle.crt

# Copy host system certificates to ensure complete CA coverage  
# This fixes Docker container certificate isolation issues
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /usr/local/share/ca-certificates/host-ca-certificates.crt

# Update certificate store with all certificates
RUN update-ca-certificates --fresh

# Test certificate validation with StreamingFast endpoint
RUN curl -I https://mainnet.sol.streamingfast.io:443 \
    || echo "WARNING: Cannot validate StreamingFast certificate, but continuing build"

# Ensure proper SSL certificate environment
ENV SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
ENV SSL_CERT_DIR=/etc/ssl/certs
ENV REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt

# Create app user (handle existing user/UID conflicts gracefully)
RUN if ! id -u sink >/dev/null 2>&1; then \
        if id -u 1000 >/dev/null 2>&1; then \
            useradd -r -m -c "sink user" -d /app -s /bin/false sink; \
        else \
            useradd -r -u 1000 -m -c "sink user" -d /app -s /bin/false sink; \
        fi; \
    fi

# Create app directory and logs directory
WORKDIR /app
RUN mkdir -p /app/logs && chown -R sink:sink /app

# Copy binary from builder stage (workspace target directory)
COPY --from=builder /workspace/target/release/substreams-nats-sink /app/substreams-nats-sink

# Set ownership and permissions
RUN chown sink:sink /app/substreams-nats-sink && \
    chmod +x /app/substreams-nats-sink

# Switch to non-root user
USER sink

# Expose health check port (if needed in future)
EXPOSE 8090

# Set default environment variables for NATS sink
ENV LOG_LEVEL=info
ENV NATS_SERVER_URL=nats-server:4222
ENV NATS_SUBJECT_PREFIX=blockchain
ENV NATS_STREAM_NAME=substreams

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD pgrep substreams-nats-sink > /dev/null || exit 1

# Run the NATS-specific sink
ENTRYPOINT ["/app/substreams-nats-sink"]