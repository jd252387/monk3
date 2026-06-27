package jd.nomad.mapping;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JqSourceFieldExtractorTest {

    private JqSourceFieldExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new JqSourceFieldExtractor();
    }

    @Test
    void shouldExtractSimpleDotNotation() {
        Optional<String> result = extractor.extractSourceField(".timestamp");
        assertTrue(result.isPresent());
        assertEquals("timestamp", result.get());
    }

    @Test
    void shouldExtractBracketNotation() {
        Optional<String> result = extractor.extractSourceField(".[\"item-name\"]");
        assertTrue(result.isPresent());
        assertEquals("item-name", result.get());
    }

    @Test
    void shouldExtractBracketNotationWithSpaces() {
        Optional<String> result = extractor.extractSourceField(".[\"item content\"]");
        assertTrue(result.isPresent());
        assertEquals("item content", result.get());
    }

    @Test
    void shouldExtractRootFieldFromNestedArray() {
        Optional<String> result = extractor.extractSourceField(".subItems[].itemName");
        assertTrue(result.isPresent());
        assertEquals("subItems", result.get());
    }

    @Test
    void shouldExtractRootFieldFromNestedArrayWithBracketNotation() {
        Optional<String> result = extractor.extractSourceField(".subItems[].name");
        assertTrue(result.isPresent());
        assertEquals("subItems", result.get());
    }

    @Test
    void shouldExtractRootFieldFromNestedObject() {
        Optional<String> result = extractor.extractSourceField(".foo.bar.baz");
        assertTrue(result.isPresent());
        assertEquals("foo", result.get());
    }

    @Test
    void shouldExtractRootFieldFromComplexNesting() {
        Optional<String> result = extractor.extractSourceField(".data.items[].value");
        assertTrue(result.isPresent());
        assertEquals("data", result.get());
    }

    @Test
    void shouldReturnEmptyForIdentityExpression() {
        Optional<String> result = extractor.extractSourceField(".");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForNullExpression() {
        Optional<String> result = extractor.extractSourceField(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyExpression() {
        Optional<String> result = extractor.extractSourceField("");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForBlankExpression() {
        Optional<String> result = extractor.extractSourceField("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleExpressionWithWhitespace() {
        Optional<String> result = extractor.extractSourceField("  .timestamp  ");
        assertTrue(result.isPresent());
        assertEquals("timestamp", result.get());
    }

    @Test
    void shouldExtractFieldWithUnderscores() {
        Optional<String> result = extractor.extractSourceField(".item_name");
        assertTrue(result.isPresent());
        assertEquals("item_name", result.get());
    }

    @Test
    void shouldExtractFieldWithNumbers() {
        Optional<String> result = extractor.extractSourceField(".field123");
        assertTrue(result.isPresent());
        assertEquals("field123", result.get());
    }

    @Test
    void shouldExtractFromArrayIndexing() {
        Optional<String> result = extractor.extractSourceField(".items[0].name");
        assertTrue(result.isPresent());
        assertEquals("items", result.get());
    }
}
