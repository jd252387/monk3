package jd.nomad.routing;

import java.util.List;

public record RoutingRule(List<RoutingCondition> conditions, String backend) {}
