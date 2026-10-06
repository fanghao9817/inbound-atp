package com.haoyu.inbound.orders;

/**
 * Where an order came from. FEED = the storefront, B2B and store systems (via /api/internal);
 * VISITOR = someone trying the public demo; SEED / MIGRATED = generated or converted data.
 * Flow KPIs (orders per day, week over week) count FEED orders only.
 */
public enum Origin { FEED, VISITOR, SEED, MIGRATED }
