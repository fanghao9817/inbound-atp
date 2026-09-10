{# Fractional days from `earlier` to `later`, in each warehouse's dialect. #}
{% macro days_between(later, earlier) -%}
    {%- if target.type == 'databricks' -%}
        (unix_timestamp({{ later }}) - unix_timestamp({{ earlier }})) / 86400.0
    {%- else -%}
        extract(epoch from ({{ later }} - {{ earlier }})) / 86400.0
    {%- endif -%}
{%- endmacro %}
