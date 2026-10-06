{# The business day of a timestamp: the calendar date in Vancouver, in each warehouse's dialect. #}
{% macro local_date(ts) -%}
    {%- if target.type == 'databricks' -%}
        to_date(from_utc_timestamp({{ ts }}, 'America/Vancouver'))
    {%- else -%}
        cast(({{ ts }} at time zone 'America/Vancouver') as date)
    {%- endif -%}
{%- endmacro %}
