import type { CSSProperties, ReactNode } from 'react';
import {
  BOOL_STYLE,
  PAYLOADS,
  PH,
  VTYPE_COLOR,
  onEnterBlur,
  type BoolTag,
  type EditorApi,
  type ExpansionNode,
  type Sel,
} from './model';

const CYCLE: Record<BoolTag, BoolTag> = { must: 'should', should: 'mustNot', mustNot: 'must' };

const TYPE_HINTS: Record<string, string> = {
  string: 'accepts one leaf payload',
  freetext: 'accepts one leaf payload',
  number: 'accepts one leaf payload',
  datetime: 'accepts one leaf payload',
  boolean: 'accepts one leaf payload',
  predicate: 'queried by name alone — expansion is static',
  subquery: 'accepts a boolean clause array',
};

const SMALL_INPUT: CSSProperties = {
  background: '#12141a',
  border: '1px solid #2f3440',
  borderRadius: 4,
  padding: '3px 6px',
  font: "400 11px 'IBM Plex Mono',monospace",
  color: '#e6e9ef',
  outline: 'none',
};

const X_BTN: CSSProperties = {
  font: "400 11px 'IBM Plex Sans',sans-serif",
  color: '#5b6270',
  background: 'transparent',
  border: 'none',
  cursor: 'pointer',
};

const DASHED_BTN: CSSProperties = {
  font: "500 10.5px 'IBM Plex Sans',sans-serif",
  color: '#9fc9e8',
  border: '1px dashed #3a4a5a',
  borderRadius: 6,
  padding: '4px 10px',
  background: 'transparent',
  cursor: 'pointer',
};

export default function VirtualInspector({ api, sel, vErrList }: { api: EditorApi; sel: Sel; vErrList: string[] }) {
  const { block: b, field: f } = sel;
  const { state, patch, upd } = api;
  const d = state.virtual[b][f];
  const blocks = Object.keys(state.mapping);

  const updNode = (path: number[], fn: (exp: ExpansionNode, node: ExpansionNode) => void) => {
    upd((st) => {
      st.vJsonEditing = false;
      st.vJsonOk = true;
      const exp = st.virtual[b][f].expansion;
      let n: ExpansionNode = exp;
      for (const i of path) n = (n.data as ExpansionNode[])[i];
      fn(exp, n);
    });
  };

  const boolChip = (path: number[], node: ExpansionNode) => {
    const bs = (node.bool && BOOL_STYLE[node.bool]) || BOOL_STYLE.must;
    return (
      <button
        onClick={() => updNode(path, (_exp, n) => (n.bool = (n.bool && CYCLE[n.bool]) || 'must'))}
        title="click to cycle must / should / mustNot"
        style={{ font: "600 9px 'IBM Plex Mono',monospace", color: bs.fg, background: bs.bg, border: 'none', borderRadius: 4, padding: '2px 7px', cursor: 'pointer' }}
      >
        {bs.label}
      </button>
    );
  };

  const delBtn = (path: number[]) => (
    <button
      className="me-hover-danger"
      onClick={() => updNode(path.slice(0, -1), (_exp, parent) => (parent.data as ExpansionNode[]).splice(path[path.length - 1], 1))}
      style={X_BTN}
    >
      ✕
    </button>
  );

  // Flat render of the expansion tree, one indented row per node (as in the design).
  const rows: ReactNode[] = [];
  const visit = (node: ExpansionNode, path: number[], depth: number) => {
    const key = path.join('.') || 'root';
    const ind = depth * 18;
    if (Array.isArray(node.data)) {
      const isNested = !!(node.field && state.mapping[node.field]);
      rows.push(
        <div key={key} style={{ marginLeft: ind }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              background: '#1c1f26',
              border: `1px solid ${isNested ? 'rgba(183,154,232,.35)' : '#2f3440'}`,
              borderRadius: 8,
              padding: '8px 11px',
            }}
          >
            {depth > 0 && boolChip(path, node)}
            <span
              style={{
                font: "600 9.5px 'IBM Plex Mono',monospace",
                color: isNested ? '#b79ae8' : '#68d8d0',
                background: isNested ? 'rgba(183,154,232,.13)' : 'rgba(104,216,208,.1)',
                borderRadius: 4,
                padding: '2px 7px',
              }}
            >
              {isNested ? 'NESTED' : 'BOOLEAN'}
            </span>
            <select
              value={node.field || ''}
              onChange={(e) => {
                const v = e.target.value;
                updNode(path, (_exp, n) => (n.field = v));
              }}
              title='empty = boolean group; a document type = nested query'
              style={{ ...SMALL_INPUT, font: "500 11px 'IBM Plex Mono',monospace", cursor: 'pointer' }}
            >
              <option value="">boolean (&quot;&quot;)</option>
              {blocks.map((blk) => (
                <option key={blk} value={blk}>
                  nested: {blk}
                </option>
              ))}
            </select>
            <span style={{ font: "400 10px 'IBM Plex Sans',sans-serif", color: '#5b6270' }}>min match</span>
            <input
              type="number"
              value={node.minimumMatch == null ? '' : String(node.minimumMatch)}
              onChange={(e) => {
                const v = e.target.value;
                updNode(path, (_exp, n) => {
                  if (v === '') delete n.minimumMatch;
                  else n.minimumMatch = parseInt(v) || 0;
                });
              }}
              placeholder="—"
              style={{ ...SMALL_INPUT, width: 44 }}
            />
            {isNested && <span style={{ font: "400 10px 'IBM Plex Sans',sans-serif", color: '#5b6270' }}>clauses match on the same child doc</span>}
            <span style={{ flex: 1 }} />
            {depth > 0 && delBtn(path)}
          </div>
        </div>,
      );
      node.data.forEach((c, i) => visit(c, [...path, i], depth + 1));
      const addClause = (mk: () => ExpansionNode) => updNode(path, (_exp, n) => (n.data as ExpansionNode[]).push(mk()));
      rows.push(
        <div key={key + ':add'} style={{ marginLeft: (depth + 1) * 18 }}>
          <div style={{ display: 'flex', gap: 7, padding: '1px 0' }}>
            <button className="me-hover-accent-bg" onClick={() => addClause(() => ({ bool: 'must', field: '', data: PAYLOADS.text.mk() }))} style={DASHED_BTN}>
              + field clause
            </button>
            <button className="me-hover-accent-bg" onClick={() => addClause(() => ({ bool: 'must', field: '', data: [] }))} style={DASHED_BTN}>
              + boolean group
            </button>
            <button
              className="me-hover-accent-bg"
              onClick={() => addClause(() => ({ bool: 'must', field: blocks.filter((x) => x !== 'root')[0] || '', data: [] }))}
              style={DASHED_BTN}
            >
              + nested (subdoc)
            </button>
            <button
              className="me-hover-ph"
              onClick={() => addClause(() => ({ bool: 'must', field: '', data: PH }))}
              style={{ ...DASHED_BTN, color: '#e88ab8', border: '1px dashed rgba(232,138,184,.35)' }}
            >
              + {PH} slot
            </button>
          </div>
        </div>,
      );
    } else if (node.data === PH) {
      rows.push(
        <div key={key} style={{ marginLeft: ind }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              background: '#1c1f26',
              border: '1px solid rgba(232,138,184,.4)',
              borderRadius: 8,
              padding: '8px 11px',
              boxShadow: '0 1px 6px rgba(232,138,184,.07)',
            }}
          >
            {boolChip(path, node)}
            <input
              className="me-focus"
              value={node.field || ''}
              onChange={(e) => {
                const v = e.target.value;
                updNode(path, (_exp, n) => (n.field = v));
              }}
              list="mfields"
              placeholder='field ("" = clause array)'
              style={{ ...SMALL_INPUT, width: 110, font: "500 11.5px 'IBM Plex Mono',monospace", color: '#eef2f8', padding: '4px 7px' }}
            />
            <span style={{ font: "500 11px 'IBM Plex Mono',monospace", color: '#e88ab8', background: 'rgba(232,138,184,.12)', borderRadius: 4, padding: '2px 8px' }}>
              {PH}
            </span>
            <span style={{ font: "400 10px 'IBM Plex Sans',sans-serif", color: '#79818f' }}>caller&apos;s payload is substituted here</span>
            <span style={{ flex: 1 }} />
            <button
              className="me-hover-light"
              onClick={() => updNode(path, (_exp, n) => (n.data = PAYLOADS.text.mk()))}
              title="replace the placeholder with a concrete payload"
              style={{
                font: "500 10px 'IBM Plex Mono',monospace",
                color: '#8b93a3',
                background: 'transparent',
                border: '1px dashed #3a4050',
                borderRadius: 4,
                padding: '2px 7px',
                cursor: 'pointer',
              }}
            >
              → payload
            </button>
            {delBtn(path)}
          </div>
        </div>,
      );
    } else {
      const p = (node.data || {}) as Record<string, any>;
      const ptype = p.type && PAYLOADS[p.type] ? (p.type as string) : 'text';
      const spec = PAYLOADS[ptype];
      rows.push(
        <div key={key} style={{ marginLeft: ind }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              background: '#1c1f26',
              border: '1px solid #2a2e37',
              borderRadius: 8,
              padding: '8px 11px',
              flexWrap: 'wrap',
            }}
          >
            {boolChip(path, node)}
            <input
              className="me-focus"
              value={node.field || ''}
              onChange={(e) => {
                const v = e.target.value;
                updNode(path, (_exp, n) => (n.field = v));
              }}
              list="mfields"
              placeholder="field"
              style={{
                ...SMALL_INPUT,
                width: 110,
                font: "500 11.5px 'IBM Plex Mono',monospace",
                color: '#eef2f8',
                padding: '4px 7px',
                border: `1px solid ${node.field ? '#2f3440' : '#e07070'}`,
              }}
            />
            <select
              value={ptype}
              onChange={(e) => {
                const v = e.target.value;
                updNode(path, (_exp, n) => (n.data = PAYLOADS[v].mk()));
              }}
              style={{ ...SMALL_INPUT, color: '#c8cedb', cursor: 'pointer' }}
            >
              <option value="text">text</option>
              <option value="exact">exact</option>
              <option value="range">range</option>
              <option value="exists">exists</option>
              <option value="prefix">prefix</option>
              <option value="knnFlat">knnFlat</option>
            </select>
            {spec.args.map((a) => (
              <span key={a.l} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <span style={{ font: "400 9.5px 'IBM Plex Mono',monospace", color: '#5b6270' }}>{a.l}</span>
                <input
                  className="me-focus"
                  value={a.get(p)}
                  onChange={(e) => {
                    const v = e.target.value;
                    updNode(path, (_exp, n) => a.set(n.data as Record<string, any>, v));
                  }}
                  style={{ ...SMALL_INPUT, width: a.w, padding: '3px 7px', color: '#e8b04a' }}
                />
              </span>
            ))}
            <span style={{ flex: 1 }} />
            <button
              className="me-hover-ph"
              onClick={() => updNode(path, (_exp, n) => (n.data = PH))}
              title={'replace payload with the ' + PH + ' placeholder'}
              style={{
                font: "500 10px 'IBM Plex Mono',monospace",
                color: '#e88ab8',
                background: 'transparent',
                border: '1px dashed rgba(232,138,184,.35)',
                borderRadius: 4,
                padding: '2px 7px',
                cursor: 'pointer',
              }}
            >
              → {PH}
            </button>
            {delBtn(path)}
          </div>
        </div>,
      );
    }
  };
  visit(d.expansion, [], 0);

  const vJsonText = state.vJsonEditing ? state.vJsonDraft : JSON.stringify(d.expansion, null, 2);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {/* header */}
      <div style={{ padding: '12px 20px', borderBottom: '1px solid #2a2e37', display: 'flex', alignItems: 'center', gap: 10, flex: 'none' }}>
        <span style={{ font: "600 14px 'IBM Plex Mono',monospace", color: '#5b6270' }}>
          {b}
          <span style={{ color: '#4a5060' }}>.</span>
        </span>
        <input
          className="me-name"
          value={state.drafts.vname ?? f}
          onChange={(e) => {
            const v = e.target.value;
            patch({ drafts: { ...state.drafts, vname: v } });
          }}
          onBlur={(e) => {
            const nv = e.target.value.trim();
            upd((st) => {
              const blk = st.virtual[b];
              if (nv && nv !== f && blk[nv] == null) {
                st.virtual[b] = Object.fromEntries(Object.entries(blk).map(([k, v]) => [k === f ? nv : k, v]));
                st.selVirt = { block: b, field: nv };
              }
              st.drafts = {};
            });
          }}
          onKeyDown={onEnterBlur}
          title="Rename virtual field (Enter to apply)"
          style={{
            font: "600 15px 'IBM Plex Mono',monospace",
            color: '#eef2f8',
            background: 'transparent',
            border: '1px solid transparent',
            borderRadius: 5,
            padding: '3px 8px',
            outline: 'none',
            width: 200,
          }}
        />
        <select
          value={d.type}
          onChange={(e) =>
            upd((st) => {
              st.virtual[b][f].type = e.target.value as typeof d.type;
            })
          }
          style={{
            background: '#12141a',
            border: '1px solid #2f3440',
            borderRadius: 5,
            padding: '5px 8px',
            font: "500 11px 'IBM Plex Mono',monospace",
            color: VTYPE_COLOR[d.type] || '#e6e9ef',
            outline: 'none',
            cursor: 'pointer',
          }}
        >
          <option value="string">string</option>
          <option value="freetext">freetext</option>
          <option value="number">number</option>
          <option value="datetime">datetime</option>
          <option value="boolean">boolean</option>
          <option value="predicate">predicate</option>
          <option value="subquery">subquery</option>
        </select>
        <span style={{ font: "400 11px 'IBM Plex Sans',sans-serif", color: '#79818f' }}>{TYPE_HINTS[d.type] || ''}</span>
        <div style={{ flex: 1 }} />
        <button
          className="me-hover-danger-bg"
          onClick={() =>
            upd((st) => {
              delete st.virtual[b][f];
              const rest = Object.keys(st.virtual[b]);
              st.selVirt = rest.length ? { block: b, field: rest[0] } : null;
            })
          }
          style={{ font: "500 11px 'IBM Plex Sans',sans-serif", color: '#e07070', background: 'transparent', border: 'none', cursor: 'pointer', padding: '4px 8px' }}
        >
          Delete field
        </button>
      </div>

      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        {/* builder */}
        <div style={{ flex: 1.2, minWidth: 0, display: 'flex', flexDirection: 'column', borderRight: '1px solid #2a2e37' }}>
          <div style={{ display: 'flex', alignItems: 'center', padding: '10px 16px', borderBottom: '1px solid #22262e', flex: 'none' }}>
            <span style={{ font: "600 10px 'IBM Plex Mono',monospace", letterSpacing: '.06em', color: '#79818f' }}>EXPANSION TEMPLATE</span>
            <span style={{ font: "400 10.5px 'IBM Plex Sans',sans-serif", color: '#5b6270', marginLeft: 8 }}>
              rewrites a query on this field into real mapped fields
            </span>
          </div>
          <div style={{ flex: 1, overflowY: 'auto', padding: '14px 16px', minHeight: 0 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>{rows}</div>
            {vErrList.length > 0 ? (
              <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 5 }}>
                {vErrList.map((e, i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 7, font: "400 11px 'IBM Plex Sans',sans-serif", color: '#f2a4a4' }}>
                    <span style={{ width: 6, height: 6, flex: 'none', borderRadius: '50%', background: '#e07070' }} />
                    {e}
                  </div>
                ))}
              </div>
            ) : (
              <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', gap: 7, font: "400 11px 'IBM Plex Sans',sans-serif", color: '#8b93a3' }}>
                <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#7ee8a2' }} />
                Template valid — references only real mapped fields
              </div>
            )}
          </div>
        </div>

        {/* template JSON */}
        <div style={{ flex: 1, minWidth: 0, background: '#12141a', display: 'flex', flexDirection: 'column' }}>
          <div style={{ display: 'flex', alignItems: 'center', padding: '10px 14px', borderBottom: '1px solid #22262e', flex: 'none' }}>
            <span style={{ font: "600 10px 'IBM Plex Mono',monospace", letterSpacing: '.06em', color: '#79818f' }}>TEMPLATE JSON</span>
            <span style={{ flex: 1 }} />
            <span style={{ font: "400 10px 'IBM Plex Sans',sans-serif", color: '#5b6270' }}>editable · two-way sync</span>
          </div>
          <textarea
            value={vJsonText}
            onFocus={() => patch({ vJsonEditing: true, vJsonDraft: JSON.stringify(d.expansion, null, 2), vJsonOk: true })}
            onBlur={() => patch({ vJsonEditing: false, vJsonOk: true })}
            onChange={(e) => {
              const t = e.target.value;
              let parsed: ExpansionNode | null = null;
              let ok = false;
              try {
                parsed = JSON.parse(t);
                ok = !!parsed && typeof parsed === 'object' && !Array.isArray(parsed) && 'field' in parsed && 'data' in parsed;
              } catch {
                ok = false;
              }
              if (ok && parsed) {
                upd((st) => {
                  st.virtual[b][f].expansion = parsed!;
                  st.vJsonDraft = t;
                  st.vJsonOk = true;
                  st.vJsonEditing = true;
                });
              } else {
                patch({ vJsonDraft: t, vJsonOk: false, vJsonEditing: true });
              }
            }}
            spellCheck={false}
            style={{
              flex: 1,
              resize: 'none',
              background: 'transparent',
              border: 'none',
              outline: 'none',
              padding: '12px 14px',
              font: "400 11.5px/1.7 'IBM Plex Mono',monospace",
              color: '#c8cedb',
              minHeight: 0,
            }}
          />
          <div style={{ flex: 'none', padding: '8px 14px', borderTop: '1px solid #22262e', display: 'flex', alignItems: 'center', gap: 7 }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: state.vJsonOk ? '#7ee8a2' : '#e07070' }} />
            <span style={{ font: "400 10.5px 'IBM Plex Sans',sans-serif", color: state.vJsonOk ? '#8b93a3' : '#f2a4a4' }}>
              {state.vJsonOk ? 'valid JSON — applied to builder' : 'invalid JSON — changes not applied'}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
