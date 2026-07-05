import { TYPE_META, VTYPE_COLOR, uniqueName, type EditorApi, type Validation } from './model';

const BLOCK_HEADER_STYLE = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '7px 12px 4px',
  font: "600 10px 'IBM Plex Mono',monospace",
  letterSpacing: '.08em',
  color: '#79818f',
} as const;

const ERR_PILL_STYLE = {
  font: "600 9px 'IBM Plex Mono',monospace",
  color: '#16181d',
  background: '#e07070',
  borderRadius: 8,
  padding: '1px 6px',
} as const;

function errCount(map: Record<string, string[]>, block: string): number {
  return Object.keys(map)
    .filter((k) => k.startsWith(block + '.'))
    .reduce((a, k) => a + map[k].length, 0);
}

export function addField(api: EditorApi, b: string) {
  api.upd((st) => {
    const n = uniqueName(st.mapping[b], 'newField');
    st.mapping[b][n] = { type: 'string' };
    st.selPhys = { block: b, field: n };
    st.tab = 'physical';
    st.drafts = {};
  });
}

export function addVirt(api: EditorApi, b: string) {
  api.upd((st) => {
    const vb = (st.virtual[b] = st.virtual[b] || {});
    const n = uniqueName(vb, 'newVirtual');
    vb[n] = { type: 'freetext', expansion: { field: '', data: [] } };
    st.selVirt = { block: b, field: n };
    st.tab = 'virtual';
    st.drafts = {};
    st.vJsonEditing = false;
  });
}

export default function TreePane({ api, validation }: { api: EditorApi; validation: Validation }) {
  const { state, patch, upd } = api;
  const { errs, verrs } = validation;
  const tabPhys = state.tab === 'physical';
  const fl = state.filter.trim().toLowerCase();
  const blocks = Object.keys(state.mapping);
  let virtCount = 0;
  for (const b of Object.keys(state.virtual)) virtCount += Object.keys(state.virtual[b] || {}).length;

  const tabStyle = (active: boolean) =>
    ({
      flex: 1,
      textAlign: 'center',
      font: "600 11px 'IBM Plex Sans',sans-serif",
      color: active ? '#e6e9ef' : '#79818f',
      background: active ? '#242832' : 'transparent',
      borderRadius: '5px 5px 0 0',
      padding: '6px 0',
      border: `1px solid ${active ? '#2f3440' : 'transparent'}`,
      borderBottom: 'none',
      cursor: 'pointer',
    }) as const;

  const emptyRow = (key: string, msg: string, onAdd: () => void) => (
    <div
      key={key}
      className="me-hover-empty"
      onClick={onAdd}
      style={{
        margin: '2px 12px 2px 20px',
        padding: '6px 10px',
        border: '1px dashed #2f3440',
        borderRadius: 5,
        font: "400 10.5px 'IBM Plex Sans',sans-serif",
        color: '#5b6270',
        cursor: 'pointer',
      }}
    >
      {msg}
    </div>
  );

  return (
    <div
      style={{
        width: 276,
        flex: 'none',
        background: '#191c22',
        borderRight: '1px solid #2a2e37',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
      }}
    >
      <div style={{ display: 'flex', gap: 2, padding: '8px 8px 0', flex: 'none' }}>
        <button onClick={() => patch({ tab: 'physical', drafts: {} })} style={tabStyle(tabPhys)}>
          Physical fields
        </button>
        <button
          onClick={() => patch({ tab: 'virtual', drafts: {}, vJsonEditing: false })}
          style={{ ...tabStyle(!tabPhys), display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5 }}
        >
          Virtual{' '}
          <span style={{ font: "600 9px 'IBM Plex Mono',monospace", background: '#2a2e37', borderRadius: 8, padding: '1px 5px', color: '#9aa2b1' }}>
            {virtCount}
          </span>
        </button>
      </div>
      <div style={{ margin: '0 8px', flex: 'none' }}>
        <input
          value={state.filter}
          onChange={(e) => patch({ filter: e.target.value })}
          placeholder="⌕ filter fields…"
          style={{
            width: '100%',
            boxSizing: 'border-box',
            padding: '7px 9px',
            background: '#12141a',
            border: '1px solid #262b34',
            borderRadius: '0 0 5px 5px',
            font: "400 11px 'IBM Plex Mono',monospace",
            color: '#c8cedb',
            outline: 'none',
          }}
        />
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '8px 0', minHeight: 0 }}>
        {blocks.map((b) => {
          const colKey = tabPhys ? b : 'v:' + b;
          const col = !!state.collapsed[colKey];
          const fields = tabPhys
            ? Object.keys(state.mapping[b]).filter((f) => f !== 'identifier' && (!fl || f.toLowerCase().includes(fl)))
            : Object.keys(state.virtual[b] || {}).filter((f) => !fl || f.toLowerCase().includes(fl));
          const blockErrs = errCount(tabPhys ? errs : verrs, b);
          const onAdd = () => (tabPhys ? addField(api, b) : addVirt(api, b));
          return (
            <div key={colKey}>
              <div style={BLOCK_HEADER_STYLE}>
                <span
                  onClick={() => patch({ collapsed: { ...state.collapsed, [colKey]: !col } })}
                  style={{ cursor: 'pointer', userSelect: 'none' }}
                >
                  {col ? '▸' : '▾'} {b.toUpperCase()}
                </span>
                <span style={{ fontWeight: 400, color: '#4a5060' }}>
                  {tabPhys ? (b === 'root' ? 'root document' : 'subdocument') : 'virtual fields'}
                </span>
                {blockErrs > 0 && <span style={ERR_PILL_STYLE}>{blockErrs}</span>}
                <span style={{ flex: 1 }} />
                <span className="me-hover-accent" onClick={onAdd} title="Add field" style={{ cursor: 'pointer', color: '#5b6270', fontWeight: 400 }}>
                  +
                </span>
                <span style={{ color: '#4a5060' }}>{fields.length}</span>
              </div>
              {!col &&
                (tabPhys
                  ? fields.map((f) => {
                      const d = state.mapping[b][f];
                      const [badge, color] = TYPE_META[d.type] || ['?', '#8b93a3'];
                      const selN = state.selPhys?.block === b && state.selPhys?.field === f;
                      const e = (errs[b + '.' + f] || []).length;
                      const flags: string[] = [];
                      if (d.aggregatable) flags.push('#e8b04a');
                      if (d.sortable) flags.push('#7ee8a2');
                      if (d.searchable === false) flags.push('#e07070');
                      return (
                        <div
                          key={f}
                          className="me-hover-row"
                          onClick={() => patch({ selPhys: { block: b, field: f }, drafts: {} })}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 8,
                            padding: '4px 12px 4px 20px',
                            cursor: 'pointer',
                            background: selN ? 'rgba(106,184,232,.14)' : 'transparent',
                            borderLeft: `2px solid ${selN ? '#6ab8e8' : 'transparent'}`,
                          }}
                        >
                          <span
                            style={{
                              font: "600 9px 'IBM Plex Mono',monospace",
                              color,
                              background: d.type === 'subdocument' ? 'transparent' : 'rgba(255,255,255,.06)',
                              border: `1px solid ${d.type === 'subdocument' ? '#3a4050' : 'transparent'}`,
                              borderRadius: 3,
                              width: 16,
                              flex: 'none',
                              textAlign: 'center',
                              boxSizing: 'border-box',
                            }}
                          >
                            {badge}
                          </span>
                          <span
                            style={{
                              font: "400 12px 'IBM Plex Mono',monospace",
                              color: selN ? '#eef2f8' : '#c8cedb',
                              whiteSpace: 'nowrap',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                            }}
                          >
                            {f}
                          </span>
                          <span style={{ flex: 1 }} />
                          {flags.map((fc, i) => (
                            <span key={i} style={{ width: 5, height: 5, flex: 'none', borderRadius: '50%', background: fc, marginLeft: 2 }} />
                          ))}
                          {d.type === 'subdocument' && (
                            <span style={{ font: "400 10px 'IBM Plex Mono',monospace", color: '#5b6270' }}>
                              → {d.subdocumentType || '?'}
                            </span>
                          )}
                          {e > 0 && <span style={ERR_PILL_STYLE}>{e}</span>}
                        </div>
                      );
                    })
                  : fields.map((f) => {
                      const d = state.virtual[b][f];
                      const selN = state.selVirt?.block === b && state.selVirt?.field === f;
                      const e = (verrs[b + '.' + f] || []).length;
                      return (
                        <div
                          key={f}
                          className="me-hover-row"
                          onClick={() => patch({ selVirt: { block: b, field: f }, drafts: {}, vJsonEditing: false })}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 8,
                            padding: '5px 12px 5px 20px',
                            cursor: 'pointer',
                            background: selN ? 'rgba(106,184,232,.14)' : 'transparent',
                            borderLeft: `2px solid ${selN ? '#6ab8e8' : 'transparent'}`,
                          }}
                        >
                          <span
                            style={{
                              font: "400 12px 'IBM Plex Mono',monospace",
                              color: selN ? '#eef2f8' : '#c8cedb',
                              whiteSpace: 'nowrap',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                            }}
                          >
                            {f}
                          </span>
                          <span
                            style={{
                              font: "500 9px 'IBM Plex Mono',monospace",
                              color: VTYPE_COLOR[d.type] || '#8b93a3',
                              background: 'rgba(255,255,255,.06)',
                              borderRadius: 3,
                              padding: '1px 5px',
                            }}
                          >
                            {d.type}
                          </span>
                          <span style={{ flex: 1 }} />
                          {e > 0 && <span style={ERR_PILL_STYLE}>{e}</span>}
                        </div>
                      );
                    }))}
              {!col &&
                fields.length === 0 &&
                emptyRow(colKey + ':empty', fl ? 'no matches' : tabPhys ? '+ add the first field' : '+ add a virtual field', onAdd)}
            </div>
          );
        })}
      </div>

      <div style={{ padding: '10px 12px', borderTop: '1px solid #2a2e37', display: 'flex', gap: 8, flex: 'none' }}>
        <button
          className="me-hover-accent-bg"
          onClick={() =>
            tabPhys
              ? upd((st) => {
                  const n = uniqueName(st.mapping, 'newBlock');
                  st.mapping[n] = {};
                  st.virtual[n] = st.virtual[n] || {};
                })
              : addVirt(api, 'root')
          }
          style={{
            flex: 1,
            textAlign: 'center',
            font: "500 11px 'IBM Plex Sans',sans-serif",
            color: '#9fc9e8',
            border: '1px dashed #3a4a5a',
            borderRadius: 5,
            padding: '6px 0',
            background: 'transparent',
            cursor: 'pointer',
          }}
        >
          {tabPhys ? '+ Document block' : '+ Virtual field on root'}
        </button>
      </div>
    </div>
  );
}
