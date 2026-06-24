package com.monk3.routing;

import com.monk3.model.BooleanQueryData;
import com.monk3.model.QueryNode;
import com.monk3.model.RangeQuery;
import com.monk3.model.TextQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryAnalyzerTest {

    @Test
    void collectsFieldFromLeafTextQuery() {
        QueryNode node = new QueryNode("title", null, null, new TextQuery("text", List.of(new TextQuery.StandardPhrase("hello", null)), null));
        QueryAnalysis analysis = QueryAnalyzer.analyze(node);
        assertTrue(analysis.queriedFields().contains("title"));
        assertTrue(analysis.datetimeRanges().isEmpty());
    }

    @Test
    void collectsDatetimeRangeBounds() {
        Instant lower = Instant.parse("2025-05-01T00:00:00Z");
        Instant upper = Instant.parse("2025-05-27T00:00:00Z");
        QueryNode node = new QueryNode("publishedAt", null, null,
                new RangeQuery.Datetime(lower, null, upper, null));

        QueryAnalysis analysis = QueryAnalyzer.analyze(node);
        assertTrue(analysis.queriedFields().contains("publishedAt"));
        List<QueryAnalysis.DatetimeRangeInfo> ranges = analysis.datetimeRanges().get("publishedAt");
        assertEquals(1, ranges.size());
        assertEquals(lower, ranges.getFirst().lowerBound());
        assertEquals(upper, ranges.getFirst().upperBound());
    }

    @Test
    void usesGtWhenGteAbsent() {
        Instant lower = Instant.parse("2025-05-01T00:00:00Z");
        Instant upper = Instant.parse("2025-05-27T00:00:00Z");
        QueryNode node = new QueryNode("publishedAt", null, null,
                new RangeQuery.Datetime(null, lower, null, upper));

        QueryAnalysis analysis = QueryAnalyzer.analyze(node);
        QueryAnalysis.DatetimeRangeInfo info = analysis.datetimeRanges().get("publishedAt").getFirst();
        assertEquals(lower, info.lowerBound());
        assertEquals(upper, info.upperBound());
    }

    @Test
    void traversesBooleanNodeClauses() {
        Instant lower = Instant.parse("2025-05-01T00:00:00Z");
        Instant upper = Instant.parse("2025-05-27T00:00:00Z");
        QueryNode titleNode = new QueryNode("title", null, null, new TextQuery("text", List.of(new TextQuery.StandardPhrase("java", null)), null));
        QueryNode dateNode = new QueryNode("publishedAt", null, null,
                new RangeQuery.Datetime(lower, null, upper, null));
        QueryNode boolNode = new QueryNode("", null, null,
                new BooleanQueryData(List.of(titleNode, dateNode)));

        QueryAnalysis analysis = QueryAnalyzer.analyze(boolNode);
        assertTrue(analysis.queriedFields().contains("title"));
        assertTrue(analysis.queriedFields().contains("publishedAt"));
        assertEquals(1, analysis.datetimeRanges().get("publishedAt").size());
    }

    @Test
    void traversesNestedSubdocumentBooleanData() {
        QueryNode innerLeaf = new QueryNode("title", null, null, new TextQuery("text", List.of(new TextQuery.StandardPhrase("intro", null)), null));
        QueryNode subdocNode = new QueryNode("chapters", null, null,
                new BooleanQueryData(List.of(innerLeaf)));

        QueryAnalysis analysis = QueryAnalyzer.analyze(subdocNode);
        assertTrue(analysis.queriedFields().contains("chapters"));
        assertTrue(analysis.queriedFields().contains("title"));
    }

    @Test
    void collectsMultipleRangesForSameField() {
        Instant lower1 = Instant.parse("2025-04-01T00:00:00Z");
        Instant upper1 = Instant.parse("2025-04-30T00:00:00Z");
        Instant lower2 = Instant.parse("2025-05-01T00:00:00Z");
        Instant upper2 = Instant.parse("2025-05-27T00:00:00Z");
        QueryNode node1 = new QueryNode("publishedAt", null, null,
                new RangeQuery.Datetime(lower1, null, upper1, null));
        QueryNode node2 = new QueryNode("publishedAt", null, null,
                new RangeQuery.Datetime(lower2, null, upper2, null));
        QueryNode boolNode = new QueryNode("", null, null,
                new BooleanQueryData(List.of(node1, node2)));

        QueryAnalysis analysis = QueryAnalyzer.analyze(boolNode);
        assertEquals(2, analysis.datetimeRanges().get("publishedAt").size());
    }
}
