/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.trace;

import static io.grpc.Metadata.ASCII_STRING_MARSHALLER;
import static io.opentelemetry.api.internal.Utils.checkArgument;
import static java.util.Objects.requireNonNull;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

/** Builder utility for this exporter. */
public final class OtlpGrpcSpanExporterBuilder {

  private static final String DEFAULT_ENDPOINT_URL = "http://localhost:4317";
  private static final URI DEFAULT_ENDPOINT = URI.create(DEFAULT_ENDPOINT_URL);
  private static final long DEFAULT_TIMEOUT_SECS = 10;

  private ManagedChannel channel;
  private long timeoutNanos = TimeUnit.SECONDS.toNanos(DEFAULT_TIMEOUT_SECS);
  private URI endpoint = DEFAULT_ENDPOINT;
  @Nullable private Metadata metadata;
  @Nullable private ByteArrayInputStream trustedCertificatesPem;
  @Nullable private ByteArrayInputStream clientCertificatePem;
  @Nullable private ByteArrayInputStream clientCertificateKeyPem;

  /**
   * Sets the managed chanel to use when communicating with the backend. Takes precedence over
   * {@link #setEndpoint(String)} if both are called.
   *
   * @param channel the channel to use
   * @return this builder's instance
   */
  public OtlpGrpcSpanExporterBuilder setChannel(ManagedChannel channel) {
    this.channel = channel;
    return this;
  }

  /**
   * Sets the maximum time to wait for the collector to process an exported batch of spans. If
   * unset, defaults to {@value DEFAULT_TIMEOUT_SECS}s.
   */
  public OtlpGrpcSpanExporterBuilder setTimeout(long timeout, TimeUnit unit) {
    requireNonNull(unit, "unit");
    checkArgument(timeout >= 0, "timeout must be non-negative");
    timeoutNanos = unit.toNanos(timeout);
    return this;
  }

  /**
   * Sets the maximum time to wait for the collector to process an exported batch of spans. If
   * unset, defaults to {@value DEFAULT_TIMEOUT_SECS}s.
   */
  public OtlpGrpcSpanExporterBuilder setTimeout(Duration timeout) {
    requireNonNull(timeout, "timeout");
    return setTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  /**
   * Sets the OTLP endpoint to connect to. If unset, defaults to {@value DEFAULT_ENDPOINT_URL}. The
   * endpoint must start with either http:// or https://.
   */
  public OtlpGrpcSpanExporterBuilder setEndpoint(String endpoint) {
    requireNonNull(endpoint, "endpoint");

    URI uri;
    try {
      uri = new URI(endpoint);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Invalid endpoint, must be a URL: " + endpoint, e);
    }

    if (uri.getScheme() == null
        || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))) {
      throw new IllegalArgumentException(
          "Invalid endpoint, must start with http:// or https://: " + uri);
    }

    this.endpoint = uri;
    return this;
  }

  /**
   * Sets the certificate chain to use for verifying servers when TLS is enabled. The {@code byte[]}
   * should contain an X.509 certificate collection in PEM format. If not set, TLS connections will
   * use the system default trusted certificates.
   */
  public OtlpGrpcSpanExporterBuilder setTrustedCertificates(byte[] trustedCertificatesPem) {
    requireNonNull(trustedCertificatesPem, "trustedCertificatesPem");

    this.trustedCertificatesPem = new ByteArrayInputStream(trustedCertificatesPem);
    return this;
  }

  /** Sets a client certificate to use for authentication when connecting to the server. */
  public OtlpGrpcSpanExporterBuilder setClientCertificate(
      byte[] clientCertificatePem, byte[] clientCertificateKeyPem) {
    requireNonNull(clientCertificatePem, "clientCertificatePem");
    requireNonNull(clientCertificateKeyPem, "clientCertificateKeyPem");

    this.clientCertificatePem = new ByteArrayInputStream(clientCertificatePem);
    this.clientCertificateKeyPem = new ByteArrayInputStream(clientCertificateKeyPem);
    return this;
  }

  /**
   * Add header to request. Optional. Applicable only if {@link
   * OtlpGrpcSpanExporterBuilder#endpoint} is set to build channel.
   *
   * @param key header key
   * @param value header value
   * @return this builder's instance
   */
  public OtlpGrpcSpanExporterBuilder addHeader(String key, String value) {
    if (metadata == null) {
      metadata = new Metadata();
    }
    metadata.put(Metadata.Key.of(key, ASCII_STRING_MARSHALLER), value);
    return this;
  }

  /**
   * Constructs a new instance of the exporter based on the builder's values.
   *
   * @return a new exporter's instance
   */
  public OtlpGrpcSpanExporter build() {
    if (channel == null) {
      final ManagedChannelBuilder<?> managedChannelBuilder =
          ManagedChannelBuilder.forTarget(endpoint.getAuthority());

      if (endpoint.getScheme().equals("https")) {
        managedChannelBuilder.useTransportSecurity();
      } else {
        managedChannelBuilder.usePlaintext();
      }

      if (metadata != null) {
        managedChannelBuilder.intercept(MetadataUtils.newAttachHeadersInterceptor(metadata));
      }

      if (trustedCertificatesPem != null || clientCertificatePem != null) {
        // gRPC does not abstract TLS configuration so we need to check the implementation and act
        // accordingly.
        if (managedChannelBuilder
            .getClass()
            .getName()
            .equals("io.grpc.netty.NettyChannelBuilder")) {
          NettyChannelBuilder nettyBuilder = (NettyChannelBuilder) managedChannelBuilder;
          try {
            nettyBuilder.sslContext(
                // SslContext handles null values for trustManager and keyManager gracefully
                GrpcSslContexts.forClient()
                    .trustManager(trustedCertificatesPem)
                    .keyManager(clientCertificatePem, clientCertificateKeyPem)
                    .build());
          } catch (IllegalArgumentException | SSLException e) {
            throw new IllegalStateException(
                "Could not set trusted certificates for gRPC TLS connection, are they valid "
                    + "X.509 in PEM format?",
                e);
          }
        } else if (managedChannelBuilder
            .getClass()
            .getName()
            .equals("io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder")) {
          io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder nettyBuilder =
              (io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder) managedChannelBuilder;
          try {
            nettyBuilder.sslContext(
                // SslContext handles null values for trustManager and keyManager gracefully
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                    .trustManager(trustedCertificatesPem)
                    .keyManager(clientCertificatePem, clientCertificateKeyPem)
                    .build());
          } catch (IllegalArgumentException | SSLException e) {
            throw new IllegalStateException(
                "Could not set trusted certificates for gRPC TLS connection, are they valid "
                    + "X.509 in PEM format?",
                e);
          }
        } else {
          throw new IllegalStateException(
              "TLS cerificate configuration only supported with Netty. "
                  + "If you need to configure a certificate, switch to grpc-netty or "
                  + "grpc-netty-shaded.");
        }
        // TODO(anuraaga): Support okhttp.
      }

      channel = managedChannelBuilder.build();
    }
    return new OtlpGrpcSpanExporter(channel, timeoutNanos);
  }

  OtlpGrpcSpanExporterBuilder() {}
}
