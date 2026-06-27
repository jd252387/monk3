Add a new aggregation type (aggType) called "filter". This aggregation type receive an "query" field within the "args" object for the aggregation. "query" will contain a query object in the monk DSL syntax, and the aggregation will be parsed to a "query facet" in Apache Solr, or a "filter aggregation" in Elasticsearch. "query" is an array of monk's QueryNode. If multiple QueryNodes are specified in the array, then they should be in a must boolean relationship. 

For example, given this query: 

{
    ..., 
    "aggs": {
        "example_agg": {
            "aggType": "filter", 
            "args": {
                "query": [
                    {
                        "field": "numValue" ,
                        "data": {
                            "type": "range", 
                            "gt": 0.6, 
                            "lt": 1.0
                        }
                    }, 
                    {
                        "field": "termValue" ,
                        "data": {
                            "type": "text",
                            "phrases": ["machine learning"]
                        }
                    }
                ]
            }
        }
    }
}

will be parsed to Solr in this way: 
{
    ..., 
    "facet": {
        "example_agg": {
            "type": "query", 
            "q": "{!v=$agg_example_agg}"
        }
    }, 
    "queries": {
        "agg_example_agg": {
            "bool": {
                "must": [
                    {
                        "frange": {
                            "query": "numValue",
                            "l": 0.6,  
                            "incl": "false", 
                            "u": 1.0,
                            "incu": "false", 
                        }
                    },
                    {
                        "field": {
                            "f": "textValue",
                            "query": "machine learning"
                        }
                    }
                ]
            }
        }
    }
}