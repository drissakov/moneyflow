FROM restic/restic:0.19.1 AS restic
FROM postgres:17.11-alpine3.23

RUN apk add --no-cache bash coreutils findutils util-linux jq ca-certificates \
    && mkdir -p /opt/moneyflow-backup /work /state /metrics
COPY --from=restic /usr/bin/restic /usr/local/bin/restic
COPY infra/backup/*.sh infra/backup/*.sql infra/backup/*.py /opt/moneyflow-backup/
RUN chmod 0755 /opt/moneyflow-backup/*.sh

ENV RESTIC_CACHE_DIR=/work/cache PGCONNECT_TIMEOUT=10 PGTZ=UTC
ENTRYPOINT ["/opt/moneyflow-backup/entrypoint.sh"]
CMD ["schedule"]
