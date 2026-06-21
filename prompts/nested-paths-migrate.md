The @config/old/migrate_mapping.py script should support a type of field in @config/old/old-mapping.json called "isLink". For example, given this "example_field" in @config/old/old-mapping.json: 

{
    "settings": {
        "fields": {
            "example_field_empty_linkedField": {
                "mapping_name": {
                    "isLink": true,
                    "linkedField": "", 
                    "nestedPaths": ["chapter", "page"]
                }
            }
        }
    }
}

It will be parsed to this virtual query: 

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