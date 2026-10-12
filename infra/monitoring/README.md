# Operational monitoring (optional VPS stack)

This directory belongs to the Docker/VPS deployment. The Vercel free deployment
does not run persistent Prometheus, Grafana or background database probes.

`METRICS_PORT=9090` enables a **separate private HTTP listener** in the API.
An empty value disables it for local development. `/metrics` on the public API
port remains a JSON 404. Do not publish the metrics port or route it through Caddy.

Prometheus scrapes `api:9090` every 15 seconds. Metrics contain bounded HTTP
methods, route templates and status codes, latency histograms, Go runtime/process
statistics and PostgreSQL connection pool statistics. User IDs, account IDs,
emails, transaction descriptions, URLs, queries and tokens are not labels.
Database readiness is cached from a background probe; a scrape never queries SQL.

`node-exporter:9100` reads backup metrics from the shared `/metrics` volume with
the textfile collector. The backup scheduler writes the file atomically. It
reports last-run success and last-success time for encrypted backup, integrity
check and isolated restore verification, plus backup duration. The exporter
requires neither privileged access nor a Docker socket.

Grafana provisions the Prometheus datasource and dashboard automatically:

- Datasource UID: `moneyflow-prometheus`.
- Dashboard UID: `moneyflow-operations`.
- Panels: availability, database readiness, request rate/statuses, latency,
  connection pool/waits, Go memory/goroutines, backup/check/restore ages, alerts.

Alert thresholds:

- API unreachable or database unready for 2 minutes.
- More than 5% 5xx responses or p95 above 1 second for 5 minutes, with at least
  six requests/minute; health/readiness probes are excluded.
- Backup/check/restore failed for 5 minutes, or no successful run in 26 hours.
- Missing/invalid backup metrics, stale database probe and alert delivery failure.

The default `alertmanager.yml` keeps alerts visible locally and sends no external
notifications. To enable delivery, use `alertmanager.webhook.example.yml` in a
private Compose override, mount the HTTPS receiver URL as the secret file
`/run/secrets/alert_webhook_url`, and mount that configuration at
`/etc/alertmanager/alertmanager.yml`. The receiver must accept the generic
Alertmanager JSON payload. Do not commit webhook URLs or authentication tokens.

Access monitoring through SSH tunnels to loopback ports, as described in
[`docs/PRODUCTION.md`](../../docs/PRODUCTION.md). A monitor on another machine
must also probe the public `/readyz`: monitoring on the same VPS cannot detect
its own total outage or a broken public TLS/DNS path.

References: [official Go client](https://prometheus.io/docs/guides/go-application/),
[textfile collector](https://github.com/prometheus/node_exporter#textfile-collector),
[Alertmanager receivers](https://prometheus.io/docs/alerting/latest/configuration/),
[Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/).
