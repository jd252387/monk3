#!/bin/bash
set -uo pipefail

# Backend hostnames default to the docker/ stack (solr/elasticsearch); the root compose stack
# overrides these via env to target its services (solr1/es01).
SOLR_URL="${SOLR_URL:-http://solr:8983}"
ES_URL="${ES_URL:-http://elasticsearch:9200}"

log() { echo "[init] $*"; }
fail() { echo "[init] ERROR: $*"; exit 1; }

echo "=== Waiting for services ==="

log "Waiting for Solr..."
until curl -sf "${SOLR_URL}/solr/admin/info/system" > /dev/null 2>&1; do sleep 2; done
log "Solr is ready."

log "Waiting for Elasticsearch..."
until curl -sf "${ES_URL}/_cluster/health?wait_for_status=yellow&timeout=5s" > /dev/null 2>&1; do sleep 2; done
log "Elasticsearch is ready."

echo ""
echo "=== Creating SolrCloud collection ==="
CREATE_RESP=$(curl -s "${SOLR_URL}/solr/admin/collections?action=CREATE&name=sample&numShards=1&replicationFactor=1&configSet=/opt/solr/server/solr/configsets/_default")
echo "$CREATE_RESP" | jq .
if echo "$CREATE_RESP" | jq -e '.responseHeader.status == 0' > /dev/null 2>&1; then
  log "SolrCloud collection 'sample' created."
else
  log "Collection may already exist, continuing..."
fi

echo ""
echo "=== Defining Solr schema ==="
SCHEMA_BODY='{
  "add-field": [
    {"name": "id", "type": "string", "stored": true, "indexed": true},
    {"name": "title", "type": "text_general", "stored": true, "indexed": true},
    {"name": "category", "type": "string", "stored": true, "indexed": true, "multiValued": false},
    {"name": "price", "type": "pfloat", "stored": true, "indexed": true},
    {"name": "description", "type": "text_general", "stored": true, "indexed": true}
  ]
}'
SCHEMA_RESP=$(curl -s -X POST "${SOLR_URL}/solr/sample/schema" \
  -H "Content-Type: application/json" -d "$SCHEMA_BODY")
if echo "$SCHEMA_RESP" | jq -e '.responseHeader.status == 0' > /dev/null 2>&1; then
  log "Solr schema defined."
else
  log "Schema fields may already exist, continuing..."
fi

echo ""
echo "=== Configuring Solr copyFields for _text_ ==="
COPY_RESP=$(curl -s -X POST "${SOLR_URL}/solr/sample/schema" \
  -H "Content-Type: application/json" \
  -d '{
    "add-copy-field": [
      {"source": "title", "dest": "_text_"},
      {"source": "description", "dest": "_text_"}
    ]
  }')
if echo "$COPY_RESP" | jq -e '.responseHeader.status == 0' > /dev/null 2>&1; then
  log "CopyFields configured for _text_."
else
  log "CopyFields may already exist, continuing..."
fi

echo ""
echo "=== Creating Elasticsearch index ==="
ES_RESP=$(curl -s -X PUT "${ES_URL}/sample" \
  -H "Content-Type: application/json" \
  -d '{
    "settings": {"number_of_shards": 1, "number_of_replicas": 0},
    "mappings": {
      "properties": {
        "id": {"type": "keyword"},
        "title": {"type": "text"},
        "category": {"type": "keyword"},
        "price": {"type": "float"},
        "description": {"type": "text"}
      }
    }
  }')
echo "$ES_RESP" | jq . | head -10
if echo "$ES_RESP" | jq -e '.acknowledged == true' > /dev/null 2>&1; then
  log "Elasticsearch index 'sample' created."
else
  log "ES index may already exist, continuing..."
fi

echo ""
echo "=== Indexing documents into Solr ==="
SOLR_INDEX_RESP=$(curl -s -X POST "${SOLR_URL}/solr/sample/update/json/docs?commit=true" \
  -H "Content-Type: application/json" \
  -d @/scripts/sample-docs.json)
echo "$SOLR_INDEX_RESP" | jq . | head -5
log "Documents indexed into Solr."

echo ""
echo "=== Indexing documents into Elasticsearch ==="
jq -c '.[] | {"index":{"_index":"sample","_id":.id}}, .' /scripts/sample-docs.json > /tmp/bulk.ndjson
ES_BULK_RESP=$(curl -s -X POST "${ES_URL}/_bulk" \
  -H "Content-Type: application/x-ndjson" \
  --data-binary @/tmp/bulk.ndjson)
echo "$ES_BULK_RESP" | jq '.errors'
log "Documents indexed into Elasticsearch."

# Optionally create additional (empty) SolrCloud collections used by other services in the stack
# (the nomad indexer writes into the `documents` collection). EXTRA_COLLECTIONS is a comma-separated
# list of collection names; the schemaless _default configSet lets nomad index without a fixed schema.
# Left unset (as in the monk3-only docker/ stack) this block is skipped.
if [ -n "${EXTRA_COLLECTIONS:-}" ]; then
  echo ""
  echo "=== Creating extra SolrCloud collections ==="
  IFS=',' read -ra extra_collections <<< "${EXTRA_COLLECTIONS}"
  for collection in "${extra_collections[@]}"; do
    collection="$(echo "$collection" | xargs)"
    [ -z "$collection" ] && continue
    EXTRA_RESP=$(curl -s "${SOLR_URL}/solr/admin/collections?action=CREATE&name=${collection}&numShards=1&replicationFactor=1&configSet=/opt/solr/server/solr/configsets/_default")
    if echo "$EXTRA_RESP" | jq -e '.responseHeader.status == 0' > /dev/null 2>&1; then
      log "SolrCloud collection '${collection}' created."
    else
      log "Collection '${collection}' may already exist, continuing..."
    fi
  done
fi

echo ""
echo "=== Initialization complete ==="
