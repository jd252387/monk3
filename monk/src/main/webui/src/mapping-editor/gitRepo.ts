import { Buffer } from 'buffer';
import LightningFS from '@isomorphic-git/lightning-fs';
import git from 'isomorphic-git';
import http from 'isomorphic-git/http/web';
import type { MappingDoc, VirtualDoc } from './model';

// isomorphic-git expects the Node Buffer global, which Vite doesn't provide.
if (!('Buffer' in globalThis)) (globalThis as Record<string, unknown>).Buffer = Buffer;

// Browser-side git access to the mapping config repository. The repo URL and
// credentials are baked into the bundle at build time (VITE_MAPPINGS_GIT_* in
// .env — see .env.example); the clone lives in an IndexedDB filesystem.

const env = import.meta.env;
const DIR = '/repo';

export const gitConfigured = Boolean(env.VITE_MAPPINGS_GIT_URL);

export interface MappingEntry {
  /** Repo-relative path of the *.mapping.json file — the stable id. */
  path: string;
  /** Basename without the .mapping.json suffix. */
  name: string;
  /** Whether a sibling <name>.virtual.json exists in the repo. */
  hasVirtual: boolean;
}

export interface MappingPair {
  mapping: MappingDoc;
  virtual: VirtualDoc;
}

let fs: LightningFS;

/** `$schema` refs stripped on read, re-added on save, keyed by repo-relative path. */
const schemaRefs = new Map<string, string>();

const abs = (path: string) => DIR + '/' + path;

// isomorphic-git can't parse a relative URL. A leading-slash URL (e.g.
// /gh/<owner>/<repo> via the vite dev proxy) is same-origin — resolve it against
// the page origin so it stays same-origin (no CORS) but parses as absolute.
const resolveUrl = (u: string) => (u.startsWith('/') ? window.location.origin + u : u);
const virtualPath = (mappingPath: string) => mappingPath.replace(/\.mapping\.json$/, '.virtual.json');

function remoteOpts() {
  const token = env.VITE_MAPPINGS_GIT_TOKEN;
  return {
    http,
    corsProxy: env.VITE_MAPPINGS_GIT_CORS_PROXY || undefined,
    onAuth: token
      ? () => ({ username: env.VITE_MAPPINGS_GIT_USERNAME || token, password: token })
      : undefined,
  };
}

async function exists(absPath: string): Promise<boolean> {
  try {
    await fs.promises.stat(absPath);
    return true;
  } catch {
    return false;
  }
}

async function findMappingFiles(dir: string, out: string[]): Promise<void> {
  for (const name of await fs.promises.readdir(dir)) {
    if (name === '.git') continue;
    const p = dir + '/' + name;
    if ((await fs.promises.stat(p)).isDirectory()) await findMappingFiles(p, out);
    else if (name.endsWith('.mapping.json')) out.push(p);
  }
}

/** Wipes the local clone, clones the configured repo fresh, and lists its mappings. */
export async function loadRepo(): Promise<MappingEntry[]> {
  if (!env.VITE_MAPPINGS_GIT_URL) throw new Error('VITE_MAPPINGS_GIT_URL is not configured');
  // ponytail: fresh clone on every page load instead of pull/merge — config repos are tiny.
  fs = new LightningFS('monk-mappings', { wipe: true });
  schemaRefs.clear();
  await git.clone({
    fs,
    dir: DIR,
    url: resolveUrl(env.VITE_MAPPINGS_GIT_URL),
    ref: env.VITE_MAPPINGS_GIT_BRANCH || undefined,
    singleBranch: true,
    noTags: true,
    ...remoteOpts(),
  });
  const files: string[] = [];
  await findMappingFiles(DIR, files);
  const entries: MappingEntry[] = [];
  for (const f of files.sort()) {
    entries.push({
      path: f.slice(DIR.length + 1),
      name: f.slice(f.lastIndexOf('/') + 1, -'.mapping.json'.length),
      hasVirtual: await exists(virtualPath(f)),
    });
  }
  return entries;
}

async function readDoc<T extends object>(path: string): Promise<T> {
  const doc = JSON.parse(await fs.promises.readFile(abs(path), 'utf8'));
  if (typeof doc.$schema === 'string') {
    schemaRefs.set(path, doc.$schema);
    delete doc.$schema;
  }
  return doc as T;
}

export async function readMapping(entry: MappingEntry): Promise<MappingPair> {
  const mapping = await readDoc<MappingDoc>(entry.path);
  const virtual = entry.hasVirtual ? await readDoc<VirtualDoc>(virtualPath(entry.path)) : {};
  // Every mapping block appears in the virtual doc so the virtual tab lists them all.
  for (const block of Object.keys(mapping)) virtual[block] = virtual[block] || {};
  return { mapping, virtual };
}

async function writeDoc(path: string, doc: MappingDoc | VirtualDoc): Promise<void> {
  const schema = schemaRefs.get(path);
  const out = schema ? { $schema: schema, ...doc } : doc;
  await fs.promises.writeFile(abs(path), JSON.stringify(out, null, 2) + '\n', 'utf8');
  await git.add({ fs, dir: DIR, filepath: path });
}

/**
 * Writes the documents into the clone, commits with the given author name and
 * message, and pushes. The virtual file is only written if it already exists in
 * the repo or now has fields. Throws on rejection (e.g. the remote moved since
 * the clone) — reloading the page re-clones fresh.
 */
export async function saveMapping(
  entry: MappingEntry,
  mapping: MappingDoc,
  virtual: VirtualDoc,
  commit: { name: string; message: string },
): Promise<void> {
  await writeDoc(entry.path, mapping);
  const hasVirtualFields = Object.values(virtual).some((b) => Object.keys(b).length > 0);
  if (entry.hasVirtual || hasVirtualFields) {
    await writeDoc(virtualPath(entry.path), virtual);
    entry.hasVirtual = true;
  }
  await git.commit({
    fs,
    dir: DIR,
    message: commit.message,
    author: { name: commit.name, email: 'mapping-editor@monk' },
  });
  const result = await git.push({ fs, dir: DIR, ...remoteOpts() });
  if (!result.ok) throw new Error(result.error ?? 'push rejected — reload to get the latest revision');
}
