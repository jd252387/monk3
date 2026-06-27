package com.monk.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.routing.RoutingCondition;
import jd.nomad.routing.RoutingRule;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class RoutingEngine {

    public String resolve(String defaultBackend, List<RoutingRule> rules, QueryAnalysis analysis) {
        Instant now = Instant.now();
        for (RoutingRule rule : rules) {
            if (allMatch(rule.conditions(), analysis, now)) {
                return rule.backend();
            }
        }
        return defaultBackend;
    }

    private static boolean allMatch(List<RoutingCondition> conditions, QueryAnalysis analysis, Instant now) {
        for (RoutingCondition condition : conditions) {
            if (!evaluate(condition, analysis, now)) {
                return false;
            }
        }
        return true;
    }

    private static boolean evaluate(RoutingCondition condition, QueryAnalysis analysis, Instant now) {
        return switch (condition) {
            case RoutingCondition.FieldQueried(var field) ->
                    analysis.queriedFields().contains(field);
            case RoutingCondition.DatetimeRangeWithin(var field, var period) -> {
                List<QueryAnalysis.DatetimeRangeInfo> ranges = analysis.datetimeRanges().get(field);
                if (ranges == null || ranges.isEmpty()) {
                    yield false;
                }
                Instant threshold = now.minus(Duration.parse(period));
                yield ranges.stream().anyMatch(r -> r.lowerBound() != null && r.lowerBound().isAfter(threshold));
            }
        };
    }
}
