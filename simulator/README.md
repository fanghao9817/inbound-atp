# simulator

The outside world of inbound-atp: carriers and customs brokers, the warehouse, online and B2B customers,
and the buyer. A separate process that talks to the API exactly as those systems would — over HTTP, with
the internal token — and has no database access and no state of its own.

## Why it is built this way

- **Deterministic.** Every "random" draw is `SplittableRandom(sha256(seed | key))`, where the key names the
  thing being decided: `(PO-2041, DEPARTED_ORIGIN)` for when that container reaches port, `(FC-PAT, hour, i)`
  for the i-th online order in an hour. Restart it and it recomputes the same truth.
- **Idempotent.** Order refs (`WEB-PAT-2026100618-3`) and milestone event ids (UUID from `po|stage`) are
  derived from those keys, so a replay after a crash is a no-op in the API.
- **Late, not wrong.** If the simulator was down, a milestone is reported when it comes back, dated when it
  really happened — exactly like late EDI. Online orders more than an hour old are dropped instead (a customer
  does not wait for a website to come back).
- **No pile-up at go-live.** Containers already at sea when the simulator took over (`SIM_GO_LIVE`) have their
  next stage drawn conditional on "not before go-live" (rejection sampling over alternative draws). If that
  fails 20 times the event is spread over the two days after go-live and marked `CARRIER_EDI_RECOVERY`, which
  keeps it out of lane statistics and accuracy scoring.
- **Secret seed.** `SIM_SEED` lives only in `infra/.env` on the box: with it, anyone could compute when every
  container will arrive, which would make the prediction look better than it is.

## Parameters

| What | Value | Why |
|---|---|---|
| Network demand | 675 units / week | What the inbound pipeline (one PO per lane per week, cases of 5) can supply; see the dry run below |
| FC share | Richmond 25%, Calgary 15%, Patterson 30%, Jacksonville 30% | Each FC uses its own local time for hours and days |
| Day of week | Sun 1.20, Mon 1.15, Tue 1.00, Wed 0.95, Thu 0.95, Fri 0.85, Sat 0.90 | Furniture is browsed on weekends and bought on Sunday/Monday |
| Hour of day | 00–06 0.15, 07–11 0.7, 12–17 1.0, 18–22 1.8, 23 0.6 (normalised) | Evening shopping |
| Season | Black Friday week ×1.1–1.3, Boxing week ×1.2, January ×0.85 | |
| SKU popularity | Zipf, s = 0.8 | A few SKUs sell most |
| Units per order | sofas almost always 1, dining chairs 1–3, bar stools 2–4 | |
| Conversion | lead ≤ 14 days: 100%; 15–35: 70%; later: 30%; no date: placed and REJECTED | Long promise dates lose customers; lost demand is recorded |
| B2B | Poisson(1) per weekday, 08:00–12:00, 5–20 units, wanted 3–10 weeks out | Becomes SCHEDULED demand |
| Booked → departed | 3–7 days | Same distributions as the API's seeded history, so live data matches what dbt learned |
| Departed → port | 0.78 × lane median × lognormal(0, 0.15); 10% + 5–12 days | Port delays |
| Port → customs cleared | 1–4 days; + 7–14 days if the gateway is congested that ISO week (8% of weeks) | Congestion is announced as an ops note |
| Customs → gate-in | 2–6 days of drayage, then the next dock slot Mon–Sat 07:00–15:00 local | |
| Gate-in → goods receipt | the FC's dock-to-stock working days, 08:00–16:00 | ≈1% damaged, 2% of lines short-shipped by 1–5 |
| Shipping | Mon–Sat 07:00–19:00 local, ≥ 2 h after reservation; B2B not before the day before need-by | Sunday closed → Monday backlog |
| Cancellations | 1% of reservations in their first 24 h; 0.5% of backorders per day | |
| Buyer | Mondays 08:00 Vancouver, and once at start-up; next sailing 5–10 days out | Proposals are idempotent per ISO week |

## Calibration

`CalibrationDryRunTest` runs 26 weeks of this demand against the API's replenishment policy in memory
(f = observed weekly demand blended with a prior for the first 4 weeks, S = f × (lead time/7 + 1 review week
+ 2 safety weeks), order S − inventory position in cases of 5). After a 12-week warm-up it must serve 85–99%
of demand from stock without the median position holding more than 20 weeks of cover. Today it reports a
fill rate of about 0.97 and a median cover of about 9 weeks.

## Running

```bash
mvn verify                                   # unit tests + dry run
API_BASE_URL=http://localhost:8080 INTERNAL_API_TOKEN=... SIM_SEED=anything mvn spring-boot:run
```

In compose it starts after the API is healthy; its healthcheck is a heartbeat file the online-customer
loop touches every minute (unhealthy after two minutes without a beat). `SIM_ENABLED=false` pauses it.
