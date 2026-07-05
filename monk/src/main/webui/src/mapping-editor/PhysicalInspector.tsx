import type { CSSProperties } from 'react';
import {
  TYPE_META,
  denormSrc,
  normSrc,
  onEnterBlur,
  uniqueName,
  type EditorApi,
  type FieldType,
  type Sel,
  type SourceExpr,
} from './model';

const LABEL: CSSProperties = {
  font: "500 10px 'IBM Plex Sans',sans-serif",
  letterSpacing: '.06em',
  color: '#79818f',
  marginBottom: 5,
};

const FIELD_INPUT: CSSProperties = {
  width: '100%',
  boxSizing: 'border-box',
  background: '#12141a',
  border: '1px solid #2f3440',
  borderRadius: 5,
  padding: '7px 10px',
  font: "400 12px 'IBM Plex Mono',monospace",
  color: '#e6e9ef',
  outline: 'none',
};

const SECTION_ADD_BTN: CSSProperties = {
  font: "500 11px 'IBM Plex Sans',sans-serif",
  color: '#9fc9e8',
  background: 'transparent',
  border: 'none',
  cursor: 'pointer',
};

function ErrMsg({ msg }: { msg: string }) {
  return <div style={{ font: "400 10.5px 'IBM Plex Sans',sans-serif", color: '#f2a4a4', marginTop: 4 }}>{msg}</div>;
}

/** Rows of a per-datasource extraction table (used for both sourcing and primaryKey). */
function SrcTable({
  api,
  sel,
  prop,
  withPartial,
  emptyMsg,
}: {
  api: EditorApi;
  sel: Sel;
  prop: 'sourcing' | 'primaryKey';
  withPartial: boolean;
  emptyMsg: string;
}) {
  const { block: b, field: f } = sel;
  const { state, patch, upd } = api;
  const obj = (state.mapping[b][f][prop] ?? {}) as Record<string, SourceExpr>;
  const keys = Object.keys(obj);
  const grid = withPartial ? '110px 88px 1fr 118px 64px 30px' : '110px 88px 1fr 64px 30px';

  const modeBtn = (active: boolean, activeBg: string, activeFg: string): CSSProperties => ({
    font: "600 9px 'IBM Plex Mono',monospace",
    padding: '3px 7px',
    border: 'none',
    cursor: 'pointer',
    background: active ? activeBg : 'transparent',
    color: active ? activeFg : '#5b6270',
  });

  return (
    <div style={{ border: '1px solid #2a2e37', borderRadius: 6, overflow: 'hidden' }}>
      {withPartial && (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: grid,
            background: '#1c1f26',
            borderBottom: '1px solid #2a2e37',
            padding: '6px 12px',
            font: "600 9.5px 'IBM Plex Mono',monospace",
            letterSpacing: '.06em',
            color: '#5b6270',
          }}
        >
          <span>DATASOURCE</span>
          <span>MODE</span>
          <span>EXPRESSION</span>
          <span>PARTIAL UPD.</span>
          <span>REQ.</span>
          <span />
        </div>
      )}
      {keys.map((ds, i) => {
        const n = normSrc(obj[ds]);
        const dk = prop + ':' + i;
        const setV = (nn: typeof n) =>
          upd((st) => {
            st.mapping[b][f][prop]![ds] = denormSrc(nn);
          });
        return (
          <div
            key={i}
            style={{ display: 'grid', gridTemplateColumns: grid, alignItems: 'center', padding: '7px 12px', borderBottom: '1px solid #22262e' }}
          >
            <input
              className="me-name"
              value={state.drafts[dk] ?? ds}
              onChange={(e) => {
                const v = e.target.value;
                patch({ drafts: { ...state.drafts, [dk]: v } });
              }}
              onBlur={(e) => {
                const nv = e.target.value.trim();
                upd((st) => {
                  const o = st.mapping[b][f][prop]!;
                  if (nv && nv !== ds && o[nv] == null) {
                    st.mapping[b][f][prop] = Object.fromEntries(Object.entries(o).map(([k, v]) => [k === ds ? nv : k, v]));
                  }
                  st.drafts = {};
                });
              }}
              onKeyDown={onEnterBlur}
              style={{
                font: "500 11px 'IBM Plex Mono',monospace",
                color: '#c8cedb',
                background: 'transparent',
                border: '1px solid transparent',
                borderRadius: 4,
                padding: '3px 5px',
                outline: 'none',
                width: 90,
              }}
            />
            <span style={{ display: 'inline-flex', border: '1px solid #2f3440', borderRadius: 4, overflow: 'hidden', width: 'fit-content' }}>
              <button onClick={() => setV({ ...n, mode: 'jq' })} style={modeBtn(n.mode === 'jq', 'rgba(106,184,232,.18)', '#6ab8e8')}>
                jq
              </button>
              <button onClick={() => setV({ ...n, mode: 'ptr' })} style={modeBtn(n.mode === 'ptr', 'rgba(232,176,74,.18)', '#e8b04a')}>
                ptr
              </button>
            </span>
            <input
              className="me-focus"
              value={n.expr}
              onChange={(e) => setV({ ...n, expr: e.target.value })}
              placeholder={n.mode === 'jq' ? 'jq program, e.g. .brand.name' : 'JSON pointer, e.g. /vendor/brand'}
              style={{
                font: "400 11px 'IBM Plex Mono',monospace",
                color: '#a5d6a7',
                background: '#12141a',
                border: `1px solid ${n.expr.trim() ? '#22262e' : '#e07070'}`,
                borderRadius: 4,
                padding: '4px 8px',
                outline: 'none',
                marginRight: 10,
              }}
            />
            {withPartial && (
              <select
                value={n.partial}
                onChange={(e) => setV({ ...n, partial: e.target.value })}
                style={{
                  font: "400 10.5px 'IBM Plex Mono',monospace",
                  color: '#c8cedb',
                  background: '#12141a',
                  border: '1px solid #2f3440',
                  borderRadius: 4,
                  padding: '3px 5px',
                  outline: 'none',
                  cursor: 'pointer',
                  width: 104,
                }}
              >
                <option value="">—</option>
                <option value="set">set</option>
                <option value="add">add</option>
                <option value="add-distinct">add-distinct</option>
                <option value="remove">remove</option>
                <option value="removeregex">removeregex</option>
                <option value="inc">inc</option>
              </select>
            )}
            <button
              onClick={() => setV({ ...n, required: !n.required })}
              title="required — a missing value aborts mapping"
              style={{
                font: "400 12px 'IBM Plex Mono',monospace",
                color: n.required ? '#7ee8a2' : '#5b6270',
                background: 'transparent',
                border: 'none',
                cursor: 'pointer',
                textAlign: 'left',
                padding: '0 6px',
              }}
            >
              {n.required ? '✓' : '—'}
            </button>
            <button
              className="me-hover-danger"
              onClick={() =>
                upd((st) => {
                  delete st.mapping[b][f][prop]![ds];
                  if (Object.keys(st.mapping[b][f][prop]!).length === 0) delete st.mapping[b][f][prop];
                })
              }
              style={{
                font: "400 11px 'IBM Plex Sans',sans-serif",
                color: '#5b6270',
                background: 'transparent',
                border: 'none',
                cursor: 'pointer',
                textAlign: 'right',
              }}
            >
              ✕
            </button>
          </div>
        );
      })}
      {keys.length === 0 && (
        <div style={{ padding: '10px 12px', font: "400 11px 'IBM Plex Sans',sans-serif", color: '#5b6270' }}>{emptyMsg}</div>
      )}
    </div>
  );
}

export default function PhysicalInspector({ api, sel, ferrs }: { api: EditorApi; sel: Sel; ferrs: string[] }) {
  const { block: b, field: f } = sel;
  const { state, patch, upd } = api;
  const d = state.mapping[b][f];
  const blocks = Object.keys(state.mapping);
  const [, tColor] = TYPE_META[d.type] || ['?', '#8b93a3'];
  const errDest = ferrs.find((e) => e.includes('destinationField')) || '';
  const errStart = ferrs.find((e) => e.startsWith('start')) || '';
  const errEnd = ferrs.find((e) => e.startsWith('end')) || '';
  const errSubdoc = ferrs.find((e) => e.includes('subdocumentType') || e.includes('document type')) || '';

  const addSrcRow = (prop: 'sourcing' | 'primaryKey') =>
    upd((st) => {
      const fd = st.mapping[b][f];
      const map = (fd[prop] = fd[prop] || {});
      map[uniqueName(map, Object.keys(map).length === 0 ? 'default' : 'datasource')] = '';
    });

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
          value={state.drafts.name ?? f}
          onChange={(e) => {
            const v = e.target.value;
            patch({ drafts: { ...state.drafts, name: v } });
          }}
          onBlur={(e) => {
            const nv = e.target.value.trim();
            upd((st) => {
              const blk = st.mapping[b];
              if (nv && nv !== f && blk[nv] == null && nv !== 'identifier') {
                st.mapping[b] = Object.fromEntries(Object.entries(blk).map(([k, v]) => [k === f ? nv : k, v]));
                st.selPhys = { block: b, field: nv };
              }
              st.drafts = {};
            });
          }}
          onKeyDown={onEnterBlur}
          title="Rename field (Enter to apply)"
          style={{
            font: "600 15px 'IBM Plex Mono',monospace",
            color: '#eef2f8',
            background: 'transparent',
            border: '1px solid transparent',
            borderRadius: 5,
            padding: '3px 8px',
            outline: 'none',
            width: 220,
          }}
        />
        <span
          style={{
            font: "600 10px 'IBM Plex Mono',monospace",
            color: tColor,
            background: 'rgba(255,255,255,.05)',
            border: '1px solid #2f3440',
            padding: '2px 8px',
            borderRadius: 4,
          }}
        >
          {d.type}
        </span>
        <div style={{ flex: 1 }} />
        <button
          className="me-hover-danger-bg"
          onClick={() =>
            upd((st) => {
              delete st.mapping[b][f];
              const rest = Object.keys(st.mapping[b]).filter((x) => x !== 'identifier');
              st.selPhys = rest.length ? { block: b, field: rest[0] } : null;
            })
          }
          style={{ font: "500 11px 'IBM Plex Sans',sans-serif", color: '#e07070', background: 'transparent', border: 'none', cursor: 'pointer', padding: '4px 8px' }}
        >
          Delete field
        </button>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 16, minHeight: 0 }}>
        {/* type + destination */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div>
            <div style={LABEL}>TYPE</div>
            <select
              value={d.type}
              onChange={(e) =>
                upd((st) => {
                  const fd = st.mapping[b][f];
                  const nt = e.target.value as FieldType;
                  fd.type = nt;
                  if (nt !== 'vector') {
                    delete fd.start;
                    delete fd.end;
                  }
                  if (nt !== 'subdocument') {
                    delete fd.subdocumentType;
                    delete fd.primaryKey;
                    delete fd.partialUpdate;
                  }
                  if (nt === 'subdocument' && !fd.subdocumentType) fd.subdocumentType = '';
                })
              }
              style={{ ...FIELD_INPUT, cursor: 'pointer' }}
            >
              <option value="string">string</option>
              <option value="freetext">freetext</option>
              <option value="boolean">boolean</option>
              <option value="datetime">datetime</option>
              <option value="number">number</option>
              <option value="subdocument">subdocument</option>
              <option value="vector">vector</option>
            </select>
          </div>
          <div>
            <div style={LABEL}>
              DESTINATION FIELD{' '}
              {d.type === 'vector' && <span style={{ color: '#5b6270', letterSpacing: 0 }}>— must contain %i</span>}
            </div>
            <input
              className="me-focus"
              value={d.destinationField || ''}
              onChange={(e) =>
                upd((st) => {
                  const v = e.target.value;
                  if (v) st.mapping[b][f].destinationField = v;
                  else delete st.mapping[b][f].destinationField;
                })
              }
              placeholder="physical name (defaults to field name)"
              style={{ ...FIELD_INPUT, border: `1px solid ${errDest ? '#e07070' : '#2f3440'}` }}
            />
            {errDest && <ErrMsg msg={errDest} />}
          </div>
        </div>

        {/* vector bounds */}
        {d.type === 'vector' && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            {(
              [
                ['start', 'START (inclusive)', errStart],
                ['end', 'END (inclusive)', errEnd],
              ] as const
            ).map(([key, label, err]) => (
              <div key={key}>
                <div style={{ ...LABEL, color: err ? '#e07070' : '#79818f' }}>{label}</div>
                <input
                  className="me-focus"
                  type="number"
                  value={d[key] == null ? '' : String(d[key])}
                  onChange={(e) =>
                    upd((st) => {
                      const v = e.target.value;
                      if (v === '') delete st.mapping[b][f][key];
                      else st.mapping[b][f][key] = parseInt(v);
                    })
                  }
                  placeholder="—"
                  style={{ ...FIELD_INPUT, border: `1px solid ${err ? '#e07070' : '#2f3440'}` }}
                />
                {err && <ErrMsg msg={err} />}
              </div>
            ))}
          </div>
        )}

        {/* subdocument type */}
        {d.type === 'subdocument' && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div>
              <div style={LABEL}>SUBDOCUMENT TYPE</div>
              <select
                value={d.subdocumentType || ''}
                onChange={(e) =>
                  upd((st) => {
                    st.mapping[b][f].subdocumentType = e.target.value;
                  })
                }
                style={{ ...FIELD_INPUT, border: `1px solid ${errSubdoc ? '#e07070' : '#2f3440'}`, cursor: 'pointer' }}
              >
                <option value="">— choose block —</option>
                {blocks.map((blk) => (
                  <option key={blk} value={blk}>
                    {blk}
                  </option>
                ))}
              </select>
              {errSubdoc && <ErrMsg msg={errSubdoc} />}
            </div>
          </div>
        )}

        {/* capabilities */}
        <div>
          <div style={{ ...LABEL, marginBottom: 7 }}>QUERY CAPABILITIES</div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {(
              [
                ['searchable', true],
                ['fetchable', true],
                ['aggregatable', false],
                ['sortable', false],
              ] as const
            ).map(([k, def]) => {
              const on = d[k] !== undefined ? !!d[k] : def;
              return (
                <button
                  key={k}
                  onClick={() =>
                    upd((st) => {
                      const nv = !on;
                      if (nv === def) delete st.mapping[b][f][k];
                      else st.mapping[b][f][k] = nv;
                    })
                  }
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 7,
                    background: on ? '#242832' : '#1c1f26',
                    border: `1px solid ${on ? '#3a4050' : '#2a2e37'}`,
                    borderRadius: 5,
                    padding: '6px 11px',
                    font: "500 11px 'IBM Plex Sans',sans-serif",
                    color: on ? '#e6e9ef' : '#79818f',
                    cursor: 'pointer',
                  }}
                >
                  <span style={{ width: 22, height: 12, borderRadius: 6, background: on ? '#6ab8e8' : '#3a4050', position: 'relative', display: 'inline-block' }}>
                    <span
                      style={{
                        position: 'absolute',
                        top: 1,
                        left: on ? 11 : 1,
                        width: 10,
                        height: 10,
                        borderRadius: '50%',
                        background: on ? '#101216' : '#79818f',
                        transition: 'left .15s',
                      }}
                    />
                  </span>
                  {k}
                </button>
              );
            })}
          </div>
        </div>

        {/* sourcing */}
        <div>
          <div style={{ display: 'flex', alignItems: 'center', marginBottom: 7 }}>
            <span style={{ ...LABEL, marginBottom: 0 }}>
              SOURCING <span style={{ color: '#5b6270', letterSpacing: 0 }}>— indexer-side extraction per datasource</span>
            </span>
            <span style={{ flex: 1 }} />
            <button onClick={() => addSrcRow('sourcing')} style={SECTION_ADD_BTN}>
              + Add datasource
            </button>
          </div>
          <SrcTable api={api} sel={sel} prop="sourcing" withPartial emptyMsg="No sourcing — this field is not extracted by the indexer." />
        </div>

        {/* primary key (subdocument only) */}
        {d.type === 'subdocument' && (
          <div>
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 7 }}>
              <span style={{ ...LABEL, marginBottom: 0 }}>
                PRIMARY KEY <span style={{ color: '#5b6270', letterSpacing: 0 }}>— subdocument child-id extraction per datasource</span>
              </span>
              <span style={{ flex: 1 }} />
              <button onClick={() => addSrcRow('primaryKey')} style={SECTION_ADD_BTN}>
                + Add datasource
              </button>
            </div>
            <SrcTable api={api} sel={sel} prop="primaryKey" withPartial={false} emptyMsg="No primary key extraction configured." />
          </div>
        )}

        {/* morphologies */}
        <div>
          <div style={{ display: 'flex', alignItems: 'center', marginBottom: 7 }}>
            <span style={{ ...LABEL, marginBottom: 0 }}>
              MORPHOLOGIES <span style={{ color: '#5b6270', letterSpacing: 0 }}>— query-side alternate destination fields</span>
            </span>
            <span style={{ flex: 1 }} />
            <button
              onClick={() =>
                upd((st) => {
                  const fd = st.mapping[b][f];
                  fd.morphologies = fd.morphologies || {};
                  const n = uniqueName(fd.morphologies, 'english');
                  fd.morphologies[n] = '';
                })
              }
              style={SECTION_ADD_BTN}
            >
              + Add morphology
            </button>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {Object.keys(d.morphologies || {}).map((mn, i) => {
              const dk = 'morph:' + i;
              return (
                <span
                  key={i}
                  style={{ display: 'inline-flex', alignItems: 'center', gap: 6, background: '#1c1f26', border: '1px solid #2a2e37', borderRadius: 5, padding: '4px 8px' }}
                >
                  <input
                    value={state.drafts[dk] ?? mn}
                    onChange={(e) => {
                      const v = e.target.value;
                      patch({ drafts: { ...state.drafts, [dk]: v } });
                    }}
                    onBlur={(e) => {
                      const nv = e.target.value.trim();
                      upd((st) => {
                        const o = st.mapping[b][f].morphologies!;
                        if (nv && nv !== mn && o[nv] == null) {
                          st.mapping[b][f].morphologies = Object.fromEntries(Object.entries(o).map(([k, v]) => [k === mn ? nv : k, v]));
                        }
                        st.drafts = {};
                      });
                    }}
                    onKeyDown={onEnterBlur}
                    style={{ font: "500 11px 'IBM Plex Mono',monospace", color: '#c8cedb', background: 'transparent', border: 'none', outline: 'none', width: 70 }}
                  />
                  <span style={{ color: '#5b6270', fontSize: 10 }}>→</span>
                  <input
                    value={d.morphologies![mn]}
                    onChange={(e) =>
                      upd((st) => {
                        st.mapping[b][f].morphologies![mn] = e.target.value;
                      })
                    }
                    placeholder="dest field"
                    style={{ font: "400 11px 'IBM Plex Mono',monospace", color: '#a5d6a7', background: 'transparent', border: 'none', outline: 'none', width: 100 }}
                  />
                  <button
                    className="me-hover-danger"
                    onClick={() =>
                      upd((st) => {
                        delete st.mapping[b][f].morphologies![mn];
                        if (Object.keys(st.mapping[b][f].morphologies!).length === 0) delete st.mapping[b][f].morphologies;
                      })
                    }
                    style={{ font: "400 10px 'IBM Plex Sans',sans-serif", color: '#5b6270', background: 'transparent', border: 'none', cursor: 'pointer' }}
                  >
                    ✕
                  </button>
                </span>
              );
            })}
            {(!d.morphologies || Object.keys(d.morphologies).length === 0) && (
              <span style={{ font: "400 11px 'IBM Plex Sans',sans-serif", color: '#5b6270', padding: '4px 0' }}>None</span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
