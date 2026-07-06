/// <reference types="vite/client" />

import type { Environment } from 'monaco-editor';

declare global {
  // Monaco reads worker factories from this global; set in monaco-setup.ts.
  // eslint-disable-next-line no-var
  var MonacoEnvironment: Environment | undefined;

  // Build-time config for the mapping editor's git access (see .env.example).
  interface ImportMetaEnv {
    readonly VITE_MAPPINGS_GIT_URL?: string;
    readonly VITE_MAPPINGS_GIT_BRANCH?: string;
    readonly VITE_MAPPINGS_GIT_USERNAME?: string;
    readonly VITE_MAPPINGS_GIT_TOKEN?: string;
    readonly VITE_MAPPINGS_GIT_CORS_PROXY?: string;
  }
}

export {};
