"""Storefront read path: GET ?sku=CODE[&fc=CODE] over the DynamoDB availability projection."""
import json
import os
from decimal import Decimal

import boto3
from boto3.dynamodb.conditions import Key

TABLE = boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])


def _plain(v):
    if isinstance(v, Decimal):
        return int(v) if v == v.to_integral_value() else float(v)
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items() if k not in ("pk", "sk")}
    if isinstance(v, list):
        return [_plain(x) for x in v]
    return v


def _response(status, body):
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json", "Cache-Control": "public, max-age=15"},
        "body": json.dumps(body),
    }


def handler(event, _context):
    params = event.get("queryStringParameters") or {}
    sku = (params.get("sku") or "").strip().upper()
    fc = (params.get("fc") or "").strip().upper()
    if not sku:
        return _response(400, {"error": "sku is required"})
    if fc:
        res = TABLE.get_item(Key={"pk": f"SKU#{sku}", "sk": f"FC#{fc}"})
        item = res.get("Item")
        return _response(200, _plain(item)) if item else _response(404, {"error": "no projection for that sku/fc"})
    res = TABLE.query(KeyConditionExpression=Key("pk").eq(f"SKU#{sku}"))
    items = [_plain(i) for i in res.get("Items", [])]
    if not items:
        return _response(404, {"error": "no projection for that sku"})
    return _response(200, {
        "sku": sku,
        "updatedAt": max(i["updatedAt"] for i in items),
        "byFc": sorted(items, key=lambda i: i["fc"]),
    })
