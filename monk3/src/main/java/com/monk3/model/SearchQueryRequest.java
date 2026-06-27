package com.monk3.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "A search query expressed in the monk3 DSL", example = """
        {
          "id": "q1",
          "name": "Find Java books by title",
          "materialTypes": ["book"],
          "query": {
            "field": "title",
            "data": {
              "type": "text",
              "phrases": [{ "type": "phrase", "value": "java records" }]
            }
          }
        }
        """)
public record SearchQueryRequest(
        @Schema(description = "Optional client-supplied identifier echoed in logs") String id,
        @NotBlank @Schema(description = "Human-readable name for this query") String name,
        @NotEmpty @Schema(description = "Material types to search across (e.g. \"book\", \"article\")") List<@NotBlank String> materialTypes,
        @NotNull @Valid @Schema(description = "Root node of the query tree") QueryNode query
) {
}
