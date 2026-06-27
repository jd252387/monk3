package jd.nomad.mapping;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the root-level source field name from a JQ expression.
 * This is used to determine which fields to fetch from datasources that support selective field retrieval.
 */
public class JqSourceFieldExtractor {

    private static final Pattern BRACKET_NOTATION_PATTERN = Pattern.compile("^\\.\\[\"([^\"]+)\"\\]");
    private static final Pattern DOT_NOTATION_PATTERN = Pattern.compile("^\\.([^.\\[]+)");

    /**
     * Extracts the root-level field name from a JQ expression.
     *
     * @param jqExpression the JQ expression (e.g., ".timestamp", ".["item-name"]", ".subItems[].name")
     * @return the root-level source field name, or empty if the expression is identity or cannot be parsed
     */
    public Optional<String> extractSourceField(String jqExpression) {
        if (jqExpression == null || jqExpression.isBlank()) {
            return Optional.empty();
        }

        String trimmed = jqExpression.trim();

        // Identity expression - fetch all fields
        if (trimmed.equals(".")) {
            return Optional.empty();
        }

        // Try bracket notation: .["field-name"]
        Matcher bracketMatcher = BRACKET_NOTATION_PATTERN.matcher(trimmed);
        if (bracketMatcher.find()) {
            return Optional.of(bracketMatcher.group(1));
        }

        // Try dot notation: .fieldName or .field.nested or .field[].nested
        Matcher dotMatcher = DOT_NOTATION_PATTERN.matcher(trimmed);
        if (dotMatcher.find()) {
            return Optional.of(dotMatcher.group(1));
        }

        // Cannot parse - return empty
        return Optional.empty();
    }
}
