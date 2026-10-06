package com.haoyu.inbound.procurement;

/**
 * Who reported a milestone. A fixed vocabulary: free text from callers is never stored or displayed.
 * CARRIER_EDI_RECOVERY marks reports that arrived long after the fact (a feed catching up); they are
 * excluded from lane statistics and accuracy scoring.
 */
public enum MilestoneSource { CARRIER_EDI, CARRIER_EDI_RECOVERY, CUSTOMS_BROKER, WMS, BUYER, MANUAL }
