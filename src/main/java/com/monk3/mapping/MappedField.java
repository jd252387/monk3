package com.monk3.mapping;

public record MappedField(
        String logicalName,
        FieldType type,
        String subdocumentType,
        String destinationField
) {
    public String searchField() {
        return destinationField == null || destinationField.isBlank() ? logicalName : destinationField;
    }

    public boolean isSubdocument() {
        return type == FieldType.SUBDOCUMENT;
    }
}
