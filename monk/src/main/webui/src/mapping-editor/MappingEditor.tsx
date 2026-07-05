import { useRef, useState, type ReactNode } from 'react';
import './mapping-editor.css';
import { demoMapping, demoVirtual } from './demoData';
import { clone, validate, type EditorApi, type EditorState } from './model';
import JsonPane from './JsonPane';
import PhysicalInspector from './PhysicalInspector';
import TreePane from './TreePane';
import VirtualInspector from './VirtualInspector';

export default function MappingEditor({ nav }: { nav?: ReactNode }) {
  const [state, setState] = useState<EditorState>(() => ({
    tab: 'physical',
    selPhys: { block: 'root', field: 'embedding' },
    selVirt: { block: 'root', field: 'anyText' },
    mapping: clone(demoMapping),
    virtual: clone(demoVirtual),
    collapsed: {},
    filter: '',
    dirty: false,
    drafts: {},
    vJsonEditing: false,
    vJsonDraft: '',
    vJsonOk: true,
  }));
  // ponytail: in-memory "saved file" snapshot; becomes a PUT to a config API when one exists
  const savedRef = useRef(JSON.stringify({ mapping: demoMapping, virtual: demoVirtual }));

  const api: EditorApi = {
    state,
    patch: (p) => setState((s) => ({ ...s, ...p })),
    upd: (fn) =>
      setState((s) => {
        const st = { ...s, mapping: clone(s.mapping), virtual: clone(s.virtual), dirty: true };
        fn(st);
        return st;
      }),
  };

  const validation = validate(state.mapping, state.virtual);
  const { errs, verrs, total } = validation;

  const tabPhys = state.tab === 'physical';
  const fileName = tabPhys ? 'products.mapping.json' : 'products.virtual.json';
  const pd = state.selPhys ? state.mapping[state.selPhys.block]?.[state.selPhys.field] : undefined;
  const vd = state.selVirt ? state.virtual[state.selVirt.block]?.[state.selVirt.field] : undefined;
  const physSel = tabPhys && !!pd;
  const virtSel = !tabPhys && !!vd;

  const jumpToIssue = () => {
    const pk = Object.keys(errs)[0];
    const vk = Object.keys(verrs)[0];
    if (pk) {
      const [b, f] = pk.split('.');
      api.patch({ tab: 'physical', selPhys: { block: b, field: f }, drafts: {} });
    } else if (vk) {
      const [b, f] = vk.split('.');
      api.patch({ tab: 'virtual', selVirt: { block: b, field: f }, drafts: {}, vJsonEditing: false });
    }
  };

  const allFieldOpts = [
    ...new Set(Object.keys(state.mapping).flatMap((b) => Object.keys(state.mapping[b]).filter((f) => f !== 'identifier'))),
  ];

  return (
    <div
      className="me-root"
      style={{ display: 'flex', flexDirection: 'column', height: '100vh', minWidth: 1380, overflow: 'hidden' }}
    >
      {/* top bar */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          height: 44,
          flex: 'none',
          padding: '0 14px',
          background: '#1c1f26',
          borderBottom: '1px solid #2a2e37',
        }}
      >
        {nav}
        <span style={{ font: "600 12px 'IBM Plex Sans',sans-serif", color: '#e6e9ef' }}>monk3</span>
        <span style={{ color: '#4a5060', fontSize: 11 }}>/</span>
        <span style={{ font: "400 12px 'IBM Plex Sans',sans-serif", color: '#8b93a3' }}>config / mappings</span>
        <span
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            padding: '3px 9px',
            background: '#242832',
            border: '1px solid #2f3440',
            borderRadius: 5,
            font: "500 11px 'IBM Plex Mono',monospace",
            color: '#d7dce6',
          }}
        >
          {fileName}
          {state.dirty && <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#e8b04a' }} title="unsaved changes" />}
        </span>
        <div style={{ flex: 1 }} />
        {total > 0 ? (
          <button
            onClick={jumpToIssue}
            title="Jump to first issue"
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              font: "500 11px 'IBM Plex Sans',sans-serif",
              color: '#f2a4a4',
              background: 'rgba(220,90,90,.12)',
              border: '1px solid rgba(220,90,90,.3)',
              padding: '3px 9px',
              borderRadius: 5,
              cursor: 'pointer',
            }}
          >
            ● {total} {total === 1 ? 'issue' : 'issues'}
          </button>
        ) : (
          <span
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              font: "500 11px 'IBM Plex Sans',sans-serif",
              color: '#9fe8b8',
              background: 'rgba(126,232,162,.08)',
              border: '1px solid rgba(126,232,162,.2)',
              padding: '3px 9px',
              borderRadius: 5,
            }}
          >
            ✓ no issues
          </span>
        )}
        <button
          className="me-hover-light"
          onClick={() => {
            const sv = JSON.parse(savedRef.current);
            api.patch({ mapping: sv.mapping, virtual: sv.virtual, dirty: false, drafts: {}, vJsonEditing: false });
          }}
          style={{ font: "500 11px 'IBM Plex Sans',sans-serif", color: '#8b93a3', padding: '4px 10px', background: 'transparent', border: 'none', cursor: 'pointer' }}
        >
          Discard
        </button>
        <button
          className="me-hover-save"
          onClick={() => {
            savedRef.current = JSON.stringify({ mapping: state.mapping, virtual: state.virtual });
            api.patch({ dirty: false });
          }}
          style={{
            font: "600 11px 'IBM Plex Sans',sans-serif",
            color: '#101216',
            background: '#6ab8e8',
            padding: '5px 12px',
            borderRadius: 5,
            border: 'none',
            cursor: 'pointer',
          }}
        >
          Save mapping
        </button>
      </div>

      <div style={{ flex: 1, display: 'flex', alignItems: 'stretch', minHeight: 0 }}>
        <TreePane api={api} validation={validation} />

        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, minHeight: 0 }}>
          {physSel && state.selPhys && (
            <PhysicalInspector api={api} sel={state.selPhys} ferrs={errs[state.selPhys.block + '.' + state.selPhys.field] || []} />
          )}
          {virtSel && state.selVirt && (
            <VirtualInspector api={api} sel={state.selVirt} vErrList={verrs[state.selVirt.block + '.' + state.selVirt.field] || []} />
          )}
          {!physSel && !virtSel && (
            <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 8 }}>
              <span style={{ font: "400 13px 'IBM Plex Sans',sans-serif", color: '#5b6270' }}>Select a field in the tree</span>
              <span style={{ font: "400 11px 'IBM Plex Sans',sans-serif", color: '#4a5060' }}>or add one with the + buttons</span>
            </div>
          )}
        </div>

        <JsonPane
          doc={tabPhys ? state.mapping : state.virtual}
          schema={tabPhys ? './mappings.schema.json' : './virtual-mapping.schema.json'}
          sel={tabPhys ? state.selPhys : state.selVirt}
          fileName={fileName}
        />
      </div>

      <datalist id="mfields">
        {allFieldOpts.map((f) => (
          <option key={f} value={f} />
        ))}
      </datalist>
    </div>
  );
}
