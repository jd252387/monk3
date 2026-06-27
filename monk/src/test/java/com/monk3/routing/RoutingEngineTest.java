package com.monk.routing;

import jd.nomad.routing.RoutingCondition;
import jd.nomad.routing.RoutingRule;
import org.junit.jupiter.api.Test;

import com.monk.routing.QueryAnalysis;
import com.monk.routing.RoutingEngine;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutingEngineTest {

    private final RoutingEngine engine = new RoutingEngine();

    @Test
    void returnsDefaultBackendWhenNoRules() {
        QueryAnalysis analysis = new QueryAnalysis(Set.of("title"), Map.of());
        assertEquals("elastic-books", engine.resolve("elastic-books", List.of(), analysis));
    }

    @Test
    void fieldQueriedConditionMatchesWhenFieldPresent() {
        QueryAnalysis analysis = new QueryAnalysis(Set.of("publishedAt"), Map.of());
        RoutingRule rule = new RoutingRule(
                List.of(new RoutingCondition.FieldQueried("publishedAt")),
                "solr-books");
        assertEquals("solr-books", engine.resolve("elastic-books", List.of(rule), analysis));
    }

    @Test
    void fieldQueriedConditionNoMatchWhenFieldAbsent() {
        QueryAnalysis analysis = new QueryAnalysis(Set.of("title"), Map.of());
        RoutingRule rule = new RoutingRule(
                List.of(new RoutingCondition.FieldQueried("publishedAt")),
                "solr-books");
        assertEquals("elastic-books", engine.resolve("elastic-books", List.of(rule), analysis));
    }

    @Test
    void datetimeRangeWithinMatchesRecentLowerBound() {
        Instant recentLower = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant upper = Instant.now();
        QueryAnalysis analysis = new QueryAnalysis(
                Set.of("publishedAt"),
                Map.of("publishedAt", List.of(new QueryAnalysis.DatetimeRangeInfo(recentLower, upper))));

        RoutingRule rule = new RoutingRule(
                List.of(new RoutingCondition.DatetimeRangeWithin("publishedAt", "P30D")),
                "solr-books");
        assertEquals("solr-books", engine.resolve("elastic-books", List.of(rule), analysis));
    }

    @Test
    void datetimeRangeWithinNoMatchForOldLowerBound() {
        Instant oldLower = Instant.now().minus(60, ChronoUnit.DAYS);
        Instant upper = Instant.now().minus(35, ChronoUnit.DAYS);
        QueryAnalysis analysis = new QueryAnalysis(
                Set.of("publishedAt"),
                Map.of("publishedAt", List.of(new QueryAnalysis.DatetimeRangeInfo(oldLower, upper))));

        RoutingRule rule = new RoutingRule(
                List.of(new RoutingCondition.DatetimeRangeWithin("publishedAt", "P30D")),
                "solr-books");
        assertEquals("elastic-books", engine.resolve("elastic-books", List.of(rule), analysis));
    }

    @Test
    void firstMatchingRuleWins() {
        QueryAnalysis analysis = new QueryAnalysis(Set.of("title", "publishedAt"), Map.of());
        RoutingRule rule1 = new RoutingRule(
                List.of(new RoutingCondition.FieldQueried("title")),
                "solr-books");
        RoutingRule rule2 = new RoutingRule(
                List.of(new RoutingCondition.FieldQueried("publishedAt")),
                "archive-books");
        assertEquals("solr-books", engine.resolve("elastic-books", List.of(rule1, rule2), analysis));
    }

    @Test
    void allConditionsMustMatchForRule() {
        QueryAnalysis analysis = new QueryAnalysis(Set.of("title"), Map.of());
        RoutingRule rule = new RoutingRule(
                List.of(
                        new RoutingCondition.FieldQueried("title"),
                        new RoutingCondition.FieldQueried("publishedAt")),
                "solr-books");
        assertEquals("elastic-books", engine.resolve("elastic-books", List.of(rule), analysis));
    }

    @Test
    void datetimeRangeWithinNoMatchWhenNullLowerBound() {
        QueryAnalysis analysis = new QueryAnalysis(
                Set.of("publishedAt"),
                Map.of("publishedAt", List.of(new QueryAnalysis.DatetimeRangeInfo(null, Instant.now()))));

        RoutingRule rule = new RoutingRule(
                List.of(new RoutingCondition.DatetimeRangeWithin("publishedAt", "P30D")),
                "solr-books");
        assertEquals("elastic-books", engine.resolve("elastic-books", List.of(rule), analysis));
    }
}
