# Runtime image for the playback signing proxy.
# Build context = repository root, after:
#   ./gradlew :proxy:installDist
#
#   docker build -f proxy/Dockerfile -t hongguo-playback-proxy .
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 8787 --home /opt/hongguo-playback-proxy --shell /usr/sbin/nologin proxy \
    && mkdir -p /opt/hongguo-playback-proxy/metasec \
    && chown -R proxy:proxy /opt/hongguo-playback-proxy

COPY --chown=proxy:proxy proxy/build/install/proxy /opt/hongguo-playback-proxy/proxy
COPY --chown=proxy:proxy proxy/scripts /opt/hongguo-playback-proxy/proxy/scripts

WORKDIR /opt/hongguo-playback-proxy/proxy
ENV BIND=0.0.0.0 \
    PORT=8787 \
    METASEC_HOME=/opt/hongguo-playback-proxy/metasec

USER proxy
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8787/health || exit 1

CMD ["./bin/proxy"]
