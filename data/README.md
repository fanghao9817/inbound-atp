# data — dbt project

Computes `analytics.lane_lead_time_stats` (P50/P80 transit days per lane and stage) from the API's milestone log.

```bash
python3 -m venv .venv && . .venv/bin/activate && pip install dbt-postgres
export DBT_PG_HOST=localhost DBT_PG_PASSWORD=...
dbt deps  # none yet
dbt run --profiles-dir . && dbt test --profiles-dir .
curl -X POST https://<host>/api/eta/recalculate-all   # re-score open shipments with the fresh statistics
```
