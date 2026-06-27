import * as monaco from 'monaco-editor';
import { loader } from '@monaco-editor/react';
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import jsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker';

// Wire only the JSON + base editor workers (the DSL is JSON). Vite bundles these
// locally via the `?worker` imports, so nothing is fetched from a CDN.
self.MonacoEnvironment = {
  getWorker(_workerId, label) {
    if (label === 'json') {
      return new jsonWorker();
    }
    return new editorWorker();
  },
};

// Use the locally bundled monaco instance instead of @monaco-editor/react's
// default CDN loader.
loader.config({ monaco });
