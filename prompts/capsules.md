I want you to add the ability to specify the "morphology" field for TextQuery. When specified, the query will route to a morphology field, specified in the mapping configuration. 

For example, given the field "title" with this mapping configuration: 
{
    "title": {
        "type": "freetext",
        "destinationField": "title_content"
        "morphologies": {
            "english": "title_content_en"
        }
    }
}

Given this query syntax: 

{
    ...,
    "data": {
        "type": "text",
        "morphology": "english", 
        "phrases": ["machine learning"]
    }
}

When "morphology" is set to "english", route the query to the "title_content_en" field instead of the default destinationField. 

Other query types may use this feature in the future, so make sure to extract this morphology picking logic to another class, and use it in TextQuery. 