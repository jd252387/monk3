import { useEffect, useRef, useState } from 'react';
import {
  AppShell,
  Badge,
  Box,
  Button,
  Group,
  Stack,
  Text,
  Title,
  Tooltip,
} from '@mantine/core';
import Editor, { type OnMount } from '@monaco-editor/react';
import { runQuery, type QueryEndpoint, type QueryResult } from './api';
import { loadSchema } from './schema';

const SAMPLE = `{
  "query": [
    {
      "name": "example",
      "materialTypes": ["sample"],
      "query": {
        "field": "title",
        "data": {
          "type": "text",
          "phrases": [{ "type": "phrase", "value": "java" }]
        }
      }
    }
  ],
  "fields": ["title", "category", "price"],
  "size": 10
}`;

const STORAGE_KEY = 'monk.queryConsole.requestBody';

const EDITOR_OPTIONS = {
  minimap: { enabled: false },
  fontSize: 13,
  scrollBeyondLastLine: false,
  automaticLayout: true,
  tabSize: 2,
} as const;

const PANEL_STYLE = {
  flex: 1,
  minHeight: 0,
  border: '1px solid var(--mantine-color-default-border)',
  borderRadius: 'var(--mantine-radius-sm)',
  overflow: 'hidden',
};

type SchemaStatus = 'loading' | 'loaded' | 'unavailable';

function prettyPrint(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

export default function App() {
  const [query, setQuery] = useState(
    () => localStorage.getItem(STORAGE_KEY) ?? SAMPLE,
  );
  const [result, setResult] = useState<QueryResult | null>(null);
  const [running, setRunning] = useState<QueryEndpoint | null>(null);
  const [schemaStatus, setSchemaStatus] = useState<SchemaStatus>('loading');

  useEffect(() => {
    loadSchema().then((ok) => setSchemaStatus(ok ? 'loaded' : 'unavailable'));
  }, []);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, query);
  }, [query]);

  async function run(endpoint: QueryEndpoint) {
    if (running !== null) return;
    setRunning(endpoint);
    try {
      setResult(await runQuery(endpoint, query));
    } catch (e) {
      setResult({ ok: false, status: 0, ms: 0, text: String(e) });
    } finally {
      setRunning(null);
    }
  }

  // Keep a stable reference to the latest `run` so editor commands and the
  // window listener (registered once) always call the current closure.
  const runRef = useRef(run);
  runRef.current = run;

  // Register hotkeys on the editor itself so they fire while it's focused and
  // override Monaco's default Ctrl+Enter ("insert line below") binding.
  const handleEditorMount: OnMount = (editor, monaco) => {
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () =>
      runRef.current('parse'),
    );
    editor.addCommand(
      monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.Enter,
      () => runRef.current('search'),
    );
  };

  // Fallback for when focus is outside a Monaco editor (e.g. a button); the
  // editors handle the shortcut themselves via the commands registered above.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Enter' || !(e.ctrlKey || e.metaKey)) return;
      if (e.target instanceof Element && e.target.closest('.monaco-editor')) return;
      e.preventDefault();
      runRef.current(e.shiftKey ? 'search' : 'parse');
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  const schemaBadge = {
    loading: (
      <Badge color="gray" variant="light">
        schema loading…
      </Badge>
    ),
    loaded: (
      <Badge color="teal" variant="light">
        schema loaded
      </Badge>
    ),
    unavailable: (
      <Tooltip label="GET /queries/schema failed — editor works without validation">
        <Badge color="yellow" variant="light">
          schema unavailable
        </Badge>
      </Tooltip>
    ),
  }[schemaStatus];

  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Title order={4}>monk · query console</Title>
            {schemaBadge}
          </Group>
          <Group gap="sm" wrap="nowrap">
            <Button
              component="a"
              href="/q/swagger-ui"
              target="_blank"
              rel="noreferrer"
              variant="subtle"
            >
              API docs
            </Button>
            <Tooltip label="Ctrl+Enter">
              <Button
                variant="default"
                onClick={() => run('parse')}
                loading={running === 'parse'}
                disabled={running !== null}
              >
                Parse
              </Button>
            </Tooltip>
            <Tooltip label="Ctrl+Shift+Enter">
              <Button
                onClick={() => run('search')}
                loading={running === 'search'}
                disabled={running !== null}
              >
                Search
              </Button>
            </Tooltip>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Main h="100vh">
        <Box
          style={{
            display: 'grid',
            gridTemplateColumns: '1fr 1fr',
            gap: 'var(--mantine-spacing-md)',
            height: 'calc(100vh - 56px - 2 * var(--mantine-spacing-md))',
          }}
        >
          <Stack gap="xs" style={{ minHeight: 0 }}>
            <Text size="sm" fw={600} c="dimmed">
              Request body
            </Text>
            <Box style={PANEL_STYLE}>
              <Editor
                language="json"
                theme="vs-dark"
                value={query}
                onChange={(value) => setQuery(value ?? '')}
                onMount={handleEditorMount}
                options={EDITOR_OPTIONS}
              />
            </Box>
          </Stack>

          <Stack gap="xs" style={{ minHeight: 0 }}>
            <Group justify="space-between" wrap="nowrap" mih={26}>
              <Text size="sm" fw={600} c="dimmed">
                Response
              </Text>
              {result && (
                <Group gap="xs" wrap="nowrap">
                  <Badge color={result.ok ? 'teal' : 'red'} variant="light">
                    {result.status ? `HTTP ${result.status}` : 'no response'}
                  </Badge>
                  <Text size="sm" c="dimmed">
                    Request time: {result.ms.toFixed(0)} ms
                  </Text>
                </Group>
              )}
            </Group>
            <Box style={PANEL_STYLE}>
              <Editor
                language="json"
                theme="vs-dark"
                value={result ? prettyPrint(result.text) : '// Run Parse or Search to see the response here.'}
                options={{ ...EDITOR_OPTIONS, readOnly: true }}
              />
            </Box>
          </Stack>
        </Box>
      </AppShell.Main>
    </AppShell>
  );
}
