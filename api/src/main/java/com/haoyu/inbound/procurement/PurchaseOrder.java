package com.haoyu.inbound.procurement;

import java.time.LocalDate;

public record PurchaseOrder(long id, String poNumber, String supplier, String originPort, long destFcId,
                            String status, LocalDate plannedArrival) {}
