I want you to add a new type of virtual query in @
{
  "$schema": "./virtual-mapping.schema.json",
  "root": {
    "example_field_empty_linkedField": {
      "type": "subquery",
      "expansion": {
        "field": "",
        "data": [
          [
            {"field": "title", "data": "{{data}}"},
            {"field": "year", "data": {"type": "range", "gte": 2010, "lte": 2025}}
          ]
        ]
      }
    }
  }
}