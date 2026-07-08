import type { KeyboardEvent } from 'react';

// Types + pure logic for the mapping editor, ported from the Claude Design
// spec ("Mapping Editor.dc.html" in project "Mapping configurations editor").

/** The whole-string placeholder substituted with the caller's payload at query time. */
export const PH = '{{data}}';

export type FieldType =
  | 'string'
  | 'freetext'
  | 'number'
  | 'boolean'
  | 'datetime'
  | 'subdocument'
  | 'vector';

/** Either a bare jq string, or an object with exactly one of jq / jsonPointer. */
export type SourceExpr =
  | string
  | {
      jq?: string;
      jsonPointer?: string;
      partialUpdate?: string;
      required?: boolean;
    };

export interface PhysicalField {
  type: FieldType;
  destinationField?: string;
  subdocumentType?: string;
  start?: number;
  end?: number;
  searchable?: boolean;
  fetchable?: boolean;
  aggregatable?: boolean;
  sortable?: boolean;
  sourcing?: Record<string, SourceExpr>;
  primaryKey?: Record<string, SourceExpr>;
  /** Indexer-only per-datasource array op on subdocument fields; not edited here but cleared on type change. */
  partialUpdate?: Record<string, unknown>;
  morphologies?: Record<string, string>;
}

/**
 * Blocks map field name -> definition. The special 'identifier' key holds a
 * raw query node instead of a field definition and is skipped everywhere.
 */
export type MappingDoc = Record<string, Record<string, PhysicalField>>;

export type VirtualType =
  | 'string'
  | 'freetext'
  | 'number'
  | 'datetime'
  | 'boolean'
  | 'predicate'
  | 'subquery';

export type BoolTag = 'must' | 'should' | 'mustNot';

export interface ExpansionNode {
  bool?: BoolTag;
  field: string;
  minimumMatch?: number;
  /** Clause array (boolean/nested group), leaf payload object, or the PH placeholder string. */
  data: ExpansionNode[] | Record<string, unknown> | string;
}

export interface VirtualField {
  type: VirtualType;
  expansion: ExpansionNode;
}

export type VirtualDoc = Record<string, Record<string, VirtualField>>;

export interface Sel {
  block: string;
  field: string;
}

export interface EditorState {
  tab: 'physical' | 'virtual';
  selPhys: Sel | null;
  selVirt: Sel | null;
  mapping: MappingDoc;
  virtual: VirtualDoc;
  collapsed: Record<string, boolean>;
  filter: string;
  dirty: boolean;
  /** Uncommitted text of in-place rename inputs, keyed per input; committed on blur. */
  drafts: Record<string, string>;
  vJsonEditing: boolean;
  vJsonDraft: string;
  vJsonOk: boolean;
}

/** What the container hands every pane: current state plus the two ways to change it. */
export interface EditorApi {
  state: EditorState;
  /** Shallow-merge UI-only state (selection, tabs, drafts…) without touching documents. */
  patch(p: Partial<EditorState>): void;
  /** Clone the documents, apply a mutation, mark dirty. */
  upd(fn: (st: EditorState) => void): void;
}

// ---------- presentation constants ----------

/** field type -> [tree badge letter, accent color] */
export const TYPE_META: Record<string, [string, string]> = {
  string: ['S', '#6ab8e8'],
  freetext: ['F', '#b79ae8'],
  number: ['#', '#e8b04a'],
  boolean: ['B', '#7ee8a2'],
  datetime: ['D', '#e88ab8'],
  vector: ['V', '#68d8d0'],
  subdocument: ['⊞', '#9aa2b1'],
};

export const VTYPE_COLOR: Record<string, string> = {
  string: '#6ab8e8',
  freetext: '#b79ae8',
  number: '#e8b04a',
  datetime: '#e88ab8',
  boolean: '#7ee8a2',
  predicate: '#b79ae8',
  subquery: '#e8b04a',
};

export const BOOL_STYLE: Record<BoolTag, { fg: string; bg: string; label: string }> = {
  must: { fg: '#7ee8a2', bg: 'rgba(126,232,162,.13)', label: 'MUST' },
  should: { fg: '#6ab8e8', bg: 'rgba(106,184,232,.13)', label: 'SHOULD' },
  mustNot: { fg: '#e07070', bg: 'rgba(224,112,112,.13)', label: 'NOT' },
};

// ---------- leaf payload specs (expansion builder) ----------

export interface PayloadArg {
  l: string;
  w: number;
  get(p: Record<string, any>): string;
  set(p: Record<string, any>, v: string): void;
}

export interface PayloadSpec {
  mk(): Record<string, unknown>;
  args: PayloadArg[];
}

export const PAYLOADS: Record<string, PayloadSpec> = {
  text: {
    mk: () => ({ type: 'text', phrases: [{ type: 'phrase', value: '' }] }),
    args: [
      {
        l: 'phrase',
        w: 120,
        get: (p) => (p.phrases && p.phrases[0] && p.phrases[0].value) || '',
        set: (p, v) => {
          p.phrases = [{ type: 'phrase', value: v }];
        },
      },
    ],
  },
  exact: {
    mk: () => ({ type: 'exact', values: [] }),
    args: [
      {
        l: 'values',
        w: 110,
        get: (p) => (p.values || []).map(String).join(', '),
        set: (p, v) => {
          p.values = v
            .split(',')
            .map((s) => s.trim())
            .filter((s) => s !== '')
            .map((x) => (x === 'true' ? true : x === 'false' ? false : isNaN(+x) ? x : +x));
        },
      },
    ],
  },
  range: {
    mk: () => ({ type: 'range' }),
    args: [
      {
        l: 'gte',
        w: 64,
        get: (p) => (p.gte == null ? '' : String(p.gte)),
        set: (p, v) => {
          if (v === '') delete p.gte;
          else p.gte = isNaN(+v) ? v : +v;
        },
      },
      {
        l: 'lte',
        w: 64,
        get: (p) => (p.lte == null ? '' : String(p.lte)),
        set: (p, v) => {
          if (v === '') delete p.lte;
          else p.lte = isNaN(+v) ? v : +v;
        },
      },
    ],
  },
  exists: { mk: () => ({ type: 'exists' }), args: [] },
  prefix: {
    mk: () => ({ type: 'prefix', prefix: '' }),
    args: [
      {
        l: 'prefix',
        w: 110,
        get: (p) => p.prefix || '',
        set: (p, v) => {
          p.prefix = v;
        },
      },
    ],
  },
  knnFlat: {
    mk: () => ({ type: 'knnFlat', text: '' }),
    args: [
      {
        l: 'text',
        w: 110,
        get: (p) => p.text || '',
        set: (p, v) => {
          p.text = v;
        },
      },
      {
        l: 'k',
        w: 40,
        get: (p) => (p.k == null ? '' : String(p.k)),
        set: (p, v) => {
          if (v === '') delete p.k;
          else p.k = parseInt(v) || 1;
        },
      },
    ],
  },
};

// ---------- helpers ----------

export const clone = <T>(o: T): T => JSON.parse(JSON.stringify(o));

export function uniqueName(obj: Record<string, unknown>, base: string): string {
  let n = base;
  let i = 2;
  while (obj[n] != null || n === 'identifier') {
    n = base + i;
    i++;
  }
  return n;
}

export function onEnterBlur(e: KeyboardEvent) {
  if (e.key === 'Enter') (e.target as HTMLElement).blur();
}

/** Editable view of a SourceExpr: string shorthand and object form normalized. */
export interface NormSrc {
  mode: 'jq' | 'ptr';
  expr: string;
  required: boolean;
  partial: string;
}

export function normSrc(v: SourceExpr): NormSrc {
  if (typeof v === 'string') return { mode: 'jq', expr: v, required: true, partial: '' };
  const o = v || {};
  return {
    mode: o.jsonPointer != null ? 'ptr' : 'jq',
    expr: o.jsonPointer != null ? o.jsonPointer : o.jq || '',
    required: o.required !== false,
    partial: o.partialUpdate || '',
  };
}

export function denormSrc(n: NormSrc): SourceExpr {
  if (n.mode === 'jq' && n.required && !n.partial) return n.expr;
  const o: Exclude<SourceExpr, string> = {};
  if (n.mode === 'ptr') o.jsonPointer = n.expr;
  else o.jq = n.expr;
  if (!n.required) o.required = false;
  if (n.partial) o.partialUpdate = n.partial;
  return o;
}

// ---------- validation ----------

export interface Validation {
  /** physical errors keyed 'block.field' */
  errs: Record<string, string[]>;
  /** virtual errors keyed 'block.field' */
  verrs: Record<string, string[]>;
  total: number;
}

export function validate(m: MappingDoc, v: VirtualDoc): Validation {
  const errs: Record<string, string[]> = {};
  const verrs: Record<string, string[]> = {};
  const add = (map: Record<string, string[]>, k: string, msg: string) => {
    (map[k] = map[k] || []).push(msg);
  };

  for (const b of Object.keys(m)) {
    for (const [f, d] of Object.entries(m[b])) {
      if (f === 'identifier') continue;
      const k = b + '.' + f;
      if (d.type === 'vector') {
        if (!d.destinationField || !d.destinationField.includes('%i'))
          add(errs, k, 'destinationField must contain the %i placeholder');
        if (d.start == null) add(errs, k, 'start is required for vector fields');
        if (d.end == null) add(errs, k, 'end is required for vector fields');
        else if (d.start != null && d.end < d.start) add(errs, k, 'end must be ≥ start');
      }
      if (d.type === 'subdocument') {
        if (!d.subdocumentType) add(errs, k, 'subdocumentType is required');
        else if (!m[d.subdocumentType]) add(errs, k, 'unknown document type "' + d.subdocumentType + '"');
      }
      for (const [ds, se] of Object.entries(d.sourcing || {})) {
        if (!normSrc(se).expr.trim()) add(errs, k, 'sourcing "' + ds + '" has an empty expression');
      }
    }
  }

  const virtNames = new Set<string>();
  for (const b of Object.keys(v)) for (const f of Object.keys(v[b] || {})) virtNames.add(f);
  const fieldExists = (fld: string) =>
    Object.keys(m).some((b) => m[b][fld] != null && fld !== 'identifier');

  for (const b of Object.keys(v)) {
    for (const [f, d] of Object.entries(v[b] || {})) {
      const k = b + '.' + f;
      const txt = JSON.stringify(d.expansion || {});
      if (d.type === 'predicate' && txt.includes(PH))
        add(verrs, k, 'predicate expansion must not contain the ' + PH + ' placeholder');
      if (d.type !== 'predicate' && !txt.includes(PH))
        add(verrs, k, 'expansion never uses ' + PH + ' — incoming query data would be ignored');
      const walk = (n: unknown) => {
        if (!n || typeof n !== 'object' || Array.isArray(n)) return;
        const node = n as ExpansionNode;
        if (Array.isArray(node.data)) {
          node.data.forEach(walk);
          return;
        }
        const fld = node.field;
        if (fld && !m[fld] && !fieldExists(fld)) {
          add(
            verrs,
            k,
            virtNames.has(fld)
              ? 'expansion may not reference virtual field "' + fld + '"'
              : 'unknown field "' + fld + '" in expansion',
          );
        }
      };
      walk(d.expansion);
    }
  }

  let total = 0;
  for (const k of Object.keys(errs)) total += errs[k].length;
  for (const k of Object.keys(verrs)) total += verrs[k].length;
  return { errs, verrs, total };
}

// ---------- change diff (pending edits since last commit) ----------

/** One top-level key whose value differs between baseline and current. '—' = absent. */
export interface KeyChange {
  key: string;
  from: string;
  to: string;
}

export interface Change {
  scope: 'physical' | 'virtual';
  /** 'block.field' — same key style as validate(). */
  path: string;
  kind: 'added' | 'removed' | 'changed';
  /** Populated for changed physical fields; empty otherwise. */
  keys: KeyChange[];
}

/** Display a field-value scalar/object as a short string; absent -> '—'. */
function fmtVal(v: unknown): string {
  if (v === undefined) return '—';
  if (v === null || typeof v !== 'object') return String(v);
  return JSON.stringify(v);
}

/** Top-level keys of two physical field objects that differ, as from → to. */
function keyChanges(a: Record<string, unknown>, b: Record<string, unknown>): KeyChange[] {
  const out: KeyChange[] = [];
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const k of keys) {
    if (JSON.stringify(a[k]) !== JSON.stringify(b[k])) out.push({ key: k, from: fmtVal(a[k]), to: fmtVal(b[k]) });
  }
  return out;
}

/** Diff every 'block.field' between the last-committed baseline and current docs. */
export function diffMapping(
  base: { mapping: MappingDoc; virtual: VirtualDoc },
  cur: { mapping: MappingDoc; virtual: VirtualDoc },
): Change[] {
  const out: Change[] = [];

  const scan = (
    scope: 'physical' | 'virtual',
    b: Record<string, Record<string, unknown>>,
    c: Record<string, Record<string, unknown>>,
  ) => {
    const blocks = [...new Set([...Object.keys(b), ...Object.keys(c)])].sort();
    for (const blk of blocks) {
      const bf = b[blk] || {};
      const cf = c[blk] || {};
      const fields = [...new Set([...Object.keys(bf), ...Object.keys(cf)])].sort();
      for (const f of fields) {
        const inB = f in bf;
        const inC = f in cf;
        const path = blk + '.' + f;
        if (inB && !inC) out.push({ scope, path, kind: 'removed', keys: [] });
        else if (!inB && inC) out.push({ scope, path, kind: 'added', keys: [] });
        else if (JSON.stringify(bf[f]) !== JSON.stringify(cf[f])) {
          // Key detail only for physical fields; identifier (raw query) and virtual: badge only.
          const keys =
            scope === 'physical' && f !== 'identifier'
              ? keyChanges(bf[f] as Record<string, unknown>, cf[f] as Record<string, unknown>)
              : [];
          out.push({ scope, path, kind: 'changed', keys });
        }
      }
    }
  };

  scan('physical', base.mapping, cur.mapping);
  scan('virtual', base.virtual, cur.virtual);
  return out;
}
