{# Write models to exactly the schema named in the config (default: the target schema),
   instead of dbt's default <target>_<custom> concatenation. #}
{% macro generate_schema_name(custom_schema_name, node) -%}
    {{ custom_schema_name if custom_schema_name is not none else target.schema }}
{%- endmacro %}
