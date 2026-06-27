package jd.nomad.routing;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RoutingCondition.FieldQueried.class, name = "fieldQueried"),
        @JsonSubTypes.Type(value = RoutingCondition.DatetimeRangeWithin.class, name = "datetimeRangeWithin")
})
public sealed interface RoutingCondition permits RoutingCondition.FieldQueried, RoutingCondition.DatetimeRangeWithin {

    record FieldQueried(String field) implements RoutingCondition {}

    record DatetimeRangeWithin(String field, String period) implements RoutingCondition {}
}
