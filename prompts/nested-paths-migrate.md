I want you to add a new type of virtual query in the virtual mapping (like @config/mappings/book.virtual.json) called "subquery". This type of virtual query allow the "data" object to be equal to a @monk3/src/main/java/com/monk3/model/BooleanQueryData.java. 

For example, 
{
  "$schema": "./virtual-mapping.schema.json",
  "root": {
    "example_subquery_field": {
      "type": "subquery",
      "expansion": {
        "field": "chapters",
        "data": "{{data}}"
      }
    }
  }
}

Note that in the example, "chapters" is a subdocument field, so the templated {{data}} given by the user is expected to be an array of arrays (the structure of @monk3/src/main/java/com/monk3/model/BooleanQueryData.java). 