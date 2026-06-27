/// <reference types="vite/client" />

import type { Environment } from 'monaco-editor';

declare global {
  // Monaco reads worker factories from this global; set in monaco-setup.ts.
  // eslint-disable-next-line no-var
  var MonacoEnvironment: Environment | undefined;
}

export {};
