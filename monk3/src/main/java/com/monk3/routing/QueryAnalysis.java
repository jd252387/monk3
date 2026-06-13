package com.monk3.routing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record QueryAnalysis(
        Set<String> queriedFields,
        Map<String, List<DatetimeRangeInfo>> datetimeRanges
) {
    public record DatetimeRangeInfo(Instant lowerBound, Instant upperBound) {}
}
