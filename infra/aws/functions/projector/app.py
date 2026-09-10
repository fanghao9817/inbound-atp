"""Upsert SKU x FC availability items into DynamoDB.

Invoked asynchronously by the ATP service after a predicted arrival changes (and for full
refreshes). Each item carries the time it was computed; a stale item never overwrites a newer
one, so out-of-order or replayed invocations converge on the latest state.
"""
import json
import logging
import os

import boto3
from botocore.exceptions import ClientError

log = logging.getLogger()
log.setLevel(logging.INFO)

TABLE = boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])
REQUIRED = ("sku", "fc", "availableNow", "updatedAt")


def handler(event, _context):
    items = event.get("items") or []
    written, skipped_stale, rejected = 0, 0, 0
    for raw in items:
        if any(k not in raw for k in REQUIRED):
            rejected += 1
            continue
        item = {
            "pk": f"SKU#{raw['sku']}",
            "sk": f"FC#{raw['fc']}",
            "sku": raw["sku"],
            "fc": raw["fc"],
            "fcName": raw.get("fcName"),
            "availableNow": int(raw["availableNow"]),
            "promiseDate": raw.get("promiseDate"),
            "promisable": bool(raw.get("promisable", raw.get("promiseDate") is not None)),
            "confidence": raw.get("confidence"),
            "nextArrival": raw.get("nextArrival"),
            "updatedAt": raw["updatedAt"],
            "source": raw.get("source", "eta-updated"),
        }
        item = {k: v for k, v in item.items() if v is not None}
        try:
            TABLE.put_item(
                Item=item,
                ConditionExpression="attribute_not_exists(updatedAt) OR updatedAt <= :u",
                ExpressionAttributeValues={":u": item["updatedAt"]},
            )
            written += 1
        except ClientError as e:
            if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
                skipped_stale += 1
            else:
                raise
    log.info("projector: written=%d stale=%d rejected=%d source=%s", written, skipped_stale, rejected, event.get("source"))
    return {"written": written, "staleSkipped": skipped_stale, "rejected": rejected}
