FROM minio/mc:RELEASE.2025-07-21T05-28-08Z AS upstream

FROM debian:12-slim
COPY --from=upstream /usr/bin/mc /usr/bin/mc
ENTRYPOINT ["/bin/sh"]
