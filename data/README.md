# data — dbt project

Models over the API's operational tables (schema `public`, owned by Flyway), built into `analytics`.

| Model | What it is |
|---|---|
| `lane_lead_time_stats` | P50/P80 days from each stage to FC receipt, per lane, last 365 days. **The API reads this table** to predict arrivals. |
| `orders_daily` | Live demand per business day (Vancouver), FC and channel, by the decision taken when each order was placed: from stock, scheduled for the requested date, backordered, rejected; plus units shipped. |
| `inventory_movements_daily` | The append-only stock ledger rolled up per day, FC, SKU and kind (RECEIPT, RESERVE, SHIP, ...). |
| `eta_accuracy`, `eta_accuracy_by_lane` | Each received container's prediction as it stood 14 days before arrival vs the real arrival; P80 hit rate per lane. |

Tests include data contracts on the operational tables: no milestone dated in the future, the stock
ledger reconciles to the positions, reserved stock equals the RESERVED orders.

On the box this runs nightly (`scripts/nightly.sh`, systemd timer): build, test, source freshness,
docs at `/dbt/`, then the API re-scores open containers with the new statistics. The lane models also
run on Databricks weekly from a snapshot exported as seeds (`scripts/weekly-databricks.sh`).

```bash
python3 -m venv .venv && . .venv/bin/activate && pip install dbt-postgres
export DBT_PG_HOST=localhost DBT_PG_PASSWORD=...
dbt build --profiles-dir .
```
