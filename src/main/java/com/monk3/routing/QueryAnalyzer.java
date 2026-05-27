package com.monk3.routing;

import com.monk3.model.BooleanQueryData;
import com.monk3.model.QueryNode;
import com.monk3.model.RangeQuery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QueryAnalyzer {

    private QueryAnalyzer() {}

    public static QueryAnalysis analyze(QueryNode root) {
        Set<String> queriedFields = new LinkedHashSet<>();
        Map<String, List<QueryAnalysis.DatetimeRangeInfo>> datetimeRanges = new LinkedHashMap<>();
        collect(root, queriedFields, datetimeRanges);
        return new QueryAnalysis(queriedFields, datetimeRanges);
    }

    private static void collect(
            QueryNode node,
            Set<String> queriedFields,
            Map<String, List<QueryAnalysis.DatetimeRangeInfo>> datetimeRanges) {

        if (!node.field().isBlank()) {
            queriedFields.add(node.field());
        }

        if (node.data() instanceof BooleanQueryData booleanData) {
            for (List<QueryNode> clause : booleanData.clauses()) {
                for (QueryNode child : clause) {
                    collect(child, queriedFields, datetimeRanges);
                }
            }
        } else if (!node.field().isBlank() && node.data() instanceof RangeQuery.Datetime datetimeRange) {
            Instant lower = datetimeRange.gte() != null ? datetimeRange.gte() : datetimeRange.gt();
            Instant upper = datetimeRange.lte() != null ? datetimeRange.lte() : datetimeRange.lt();
            datetimeRanges.computeIfAbsent(node.field(), k -> new ArrayList<>())
                    .add(new QueryAnalysis.DatetimeRangeInfo(lower, upper));
        }
    }
}
