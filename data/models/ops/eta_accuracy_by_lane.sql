-- P80 hit rate and error per lane over the last 90 days of arrivals.
select
    origin_port,
    dest_fc_code,
    count(*)                                                          as containers,
    cast(avg(case when on_time then 1.0 else 0.0 end) as numeric(4, 3)) as p80_hit_rate,
    cast(avg(error_days) as numeric(6, 2))                            as mean_error_days,
    cast(avg(abs(error_days)) as numeric(6, 2))                       as mean_abs_error_days,
    max(error_days)                                                   as worst_late_days
from {{ ref('eta_accuracy') }}
where arrived_day >= current_date - 90
group by 1, 2
