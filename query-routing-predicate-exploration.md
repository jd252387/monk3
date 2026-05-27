I want to dynamically route each material type to different backends based on some configured rules. For example, given a query on the "book" material type, route it to the "elastic-books" backend, but if a timestamp field is queried by range and is within the last month, then route the query to the "solr-books" backend instead. 

I want you to create a "routing" stage that first analyzes the query for the following things - 
1. Which fields are queried? 
2. Which timestamp fields are queried, and what is the timestamp range are they querying? 

I then want you to change the 