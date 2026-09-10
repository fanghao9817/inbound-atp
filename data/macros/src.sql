{# Operational table by name: the live source on PostgreSQL, the seeded snapshot on Databricks. #}
{% macro src(name) -%}
    {%- if target.type == 'databricks' -%}
        {{ ref('src_' ~ name) }}
    {%- else -%}
        {{ source('inbound', name) }}
    {%- endif -%}
{%- endmacro %}
