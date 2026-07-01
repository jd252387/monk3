package com.monk.routing;

import com.monk.model.query.BooleanQueryData;
import com.monk.model.query.QueryNode;
import com.monk.model.query.RangeQuery;

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
            for (QueryNode child : booleanData.clauses()) {
                collect(child, queriedFields, datetimeRanges);
            }
        } else if (!node.field().isBlank() && node.data() instanceof RangeQuery.Datetime datetimeRange) {
            datetimeRanges.computeIfAbsent(node.field(), k -> new ArrayList<>())
                    .add(new QueryAnalysis.DatetimeRangeInfo(datetimeRange.lowerBound(), datetimeRange.upperBound()));
        }
    }
}
