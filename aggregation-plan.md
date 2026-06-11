Add a new "aggs" field to the search request. The format of the value for this field is as such: 

Example query: 
{
    "query": ..., 
    "fields": ...,
    "aggs": {
        "agg_name1": {
            "aggType": "terms", 
            "args": {
                "field": "author",
                "size": 10
            }
        },
        "agg_name2": {
            "aggType": "unique", 
            "args": {
                "field": "involved_people",
            }
        }, 
        "agg_name3": {
            "aggType": "range", 
            "args": {
                "field": "likes", 
                "interval": 500,
                "from": 1000, 
                "to": 10000
            }
        },
        "agg_name4": {
            "aggType": "subfacets", 
            "args": {
                "field": "publishedAt", 
                "filters": {
                    "lastWeek": {
                        "type": "range",
                        "gt": "2026-02-01T00:00:00Z",
                        "lt": "2026-02-07T00:00:00Z"
                    },
                    "lastMonth": {
                        "type": "range",
                        "gt": "2026-01-07T00:00:00Z",
                        "lt": "2026-02-07T00:00:00Z"
                    }
                }
            }
        }
    }
}

The "subfacets" aggType should be parsed to a facet with multiple sub-facets, with each sub-facet named by the name given in "filters". Each filter is in the Query DSL syntax, and should be parsed to the search engine syntax. 

Make sure to add this aggregation syntax to the search query DSL JSONSchema (@search-query-dsl.schema.json). 

For both Elasticsearch and Solr, use the JSON facet syntax. 

For now, only allow facets on the fields of the root document. 