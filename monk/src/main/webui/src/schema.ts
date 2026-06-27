import * as monaco from 'monaco-editor';

const SCHEMA_URI = 'inmemory://monk/search-query-dsl.schema.json';

/**
 * Fetches the query DSL JSON Schema from the live `GET /queries/schema` endpoint
 * (which enriches it with the valid field-name enums for the current mappings)
 * and registers it with Monaco's JSON language service for validation and
 * autocompletion of the editor content. Returns false if the schema could not
 * be loaded, leaving the editor usable without validation.
 */
export async function loadSchema(): Promise<boolean> {
  try {
    const res = await fetch('/queries/schema', {
      headers: { Accept: 'application/schema+json' },
    });
    if (!res.ok) {
      return false;
    }
    const schema = await res.json();
    monaco.languages.json.jsonDefaults.setDiagnosticsOptions({
      validate: true,
      enableSchemaRequest: false, // never reach out to the network to resolve $refs
      schemas: [{ uri: SCHEMA_URI, fileMatch: ['*'], schema }],
    });
    return true;
  } catch {
    return false;
  }
}
