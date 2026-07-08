import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Button, Modal, Select, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import './mapping-editor.css';
import { gitConfigured, loadRepo, readMapping, saveMapping, type MappingEntry } from './gitRepo';
import { clone, validate, type EditorApi, type EditorState } from './model';
import JsonPane from './JsonPane';
import PhysicalInspector from './PhysicalInspector';
import TreePane from './TreePane';
import VirtualInspector from './VirtualInspector';

const COMMIT_NAME_KEY = 'monk.commitName';
const DRAFT_PREFIX = 'monk.draft.';

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));

export default function MappingEditor({ nav }: { nav?: ReactNode }) {
  const [state, setState] = useState<EditorState>(() => ({
    tab: 'physical',
    selPhys: null,
    selVirt: null,
    mapping: {},
    virtual: {},
    collapsed: {},
    filter: '',
    dirty: false,
    drafts: {},
    vJsonEditing: false,
    vJsonDraft: '',
    vJsonOk: true,
  }));
  // Snapshot of the last loaded/committed documents; Discard reverts to it.
  const savedRef = useRef('');

  const [entries, setEntries] = useState<MappingEntry[] | null>(null); // null = cloning
  const [repoErr, setRepoErr] = useState<string | null>(null);
  const [selected, setSelected] = useState<MappingEntry | null>(null);

  const [saveOpen, setSaveOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveErr, setSaveErr] = useState<string | null>(null);
  const [commitName, setCommitName] = useState(() => localStorage.getItem(COMMIT_NAME_KEY) ?? '');
  const [commitMsg, setCommitMsg] = useState('');
  const [discardOpen, setDiscardOpen] = useState(false);

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

  const open = async (entry: MappingEntry) => {
    try {
      const { mapping, virtual } = await readMapping(entry);
      savedRef.current = JSON.stringify({ mapping, virtual });
      const draftJson = localStorage.getItem(DRAFT_PREFIX + entry.path);
      let dm = mapping, dv = virtual, dd: Record<string, string> = {}, isDirty = false;
      if (draftJson) {
        try {
          const d = JSON.parse(draftJson);
          dm = d.mapping; dv = d.virtual; dd = d.drafts || {}; isDirty = true;
        } catch { /* corrupt draft */ }
      }
      setSelected(entry);
      setState((s) => ({
        ...s,
        mapping: dm,
        virtual: dv,
        selPhys: null,
        selVirt: null,
        collapsed: {},
        filter: '',
        dirty: isDirty,
        drafts: dd,
        vJsonEditing: false,
      }));
    } catch (e) {
      window.alert('Failed to load ' + entry.path + ': ' + errText(e));
    }
  };

  const bootRef = useRef(false);
  useEffect(() => {
    if (bootRef.current) return; // StrictMode re-runs mount effects; clone once
    bootRef.current = true;
    if (!gitConfigured) {
      setEntries([]);
      setRepoErr('No mapping repository configured — set VITE_MAPPINGS_GIT_URL at build time (see .env.example).');
      return;
    }
    loadRepo()
      .then((es) => {
        setEntries(es);
        if (es.length === 0) setRepoErr('No *.mapping.json files found in the repository.');
        else if (es.length === 1) void open(es[0]);
      })
      .catch((e) => {
        setEntries([]);
        setRepoErr('Failed to clone the mapping repository: ' + errText(e));
      });
  }, []);

  // Persist unsaved changes so they survive a page reload.
  useEffect(() => {
    if (!selected || !state.dirty) return;
    try {
      localStorage.setItem(DRAFT_PREFIX + selected.path,
        JSON.stringify({ mapping: state.mapping, virtual: state.virtual, drafts: state.drafts }));
    } catch { /* storage full or disabled */ }
  }, [selected, state.dirty, state.mapping, state.virtual, state.drafts]);

  const pick = (path: string | null) => {
    const entry = entries?.find((x) => x.path === path);
    if (!entry || entry === selected) return;
    if (state.dirty && !window.confirm('Discard unsaved changes to ' + selected?.name + '?')) return;
    if (selected) localStorage.removeItem(DRAFT_PREFIX + selected.path);
    void open(entry);
  };

  const doSave = async () => {
    if (!selected) return;
    setSaving(true);
    setSaveErr(null);
    try {
      await saveMapping(selected, state.mapping, state.virtual, {
        name: commitName.trim(),
        message: commitMsg.trim(),
      });
      localStorage.setItem(COMMIT_NAME_KEY, commitName.trim());
      localStorage.removeItem(DRAFT_PREFIX + selected.path);
      savedRef.current = JSON.stringify({ mapping: state.mapping, virtual: state.virtual });
      api.patch({ dirty: false });
      setSaveOpen(false);
      notifications.show({
        title: 'Pushed to Git',
        message: `${selected.name} saved and pushed.`,
        color: 'green',
        autoClose: 4000,
      });
    } catch (e) {
      setSaveErr(errText(e));
    } finally {
      setSaving(false);
    }
  };

  const validation = validate(state.mapping, state.virtual);
  const { errs, verrs, total } = validation;

  const tabPhys = state.tab === 'physical';
  const fileName = selected ? selected.name + (tabPhys ? '.mapping.json' : '.virtual.json') : '';
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

  // Names are the labels; fall back to the full path when two files share a basename.
  const names = (entries ?? []).map((e) => e.name);
  const selectData = (entries ?? []).map((e) => ({
    value: e.path,
    label: names.filter((n) => n === e.name).length > 1 ? e.path : e.name,
  }));

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
        <span style={{ font: "400 12px 'IBM Plex Sans',sans-serif", color: '#8b93a3' }}>mappings</span>
        <Select
          size="xs"
          w={230}
          placeholder={entries === null ? 'Cloning repository…' : 'Select a mapping'}
          data={selectData}
          value={selected?.path ?? null}
          onChange={pick}
          disabled={!entries || entries.length === 0}
          searchable
        />
        {selected && (
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
        )}
        <div style={{ flex: 1 }} />
        {selected &&
          (total > 0 ? (
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
          ))}
        <button
          className="me-hover-light"
          disabled={!selected || !state.dirty}
          onClick={() => setDiscardOpen(true)}
          style={{ font: "500 11px 'IBM Plex Sans',sans-serif", color: '#8b93a3', padding: '4px 10px', background: 'transparent', border: 'none', cursor: 'pointer' }}
        >
          Discard
        </button>
        <button
          className="me-hover-save"
          disabled={!selected || !state.dirty}
          onClick={() => {
            setSaveErr(null);
            setCommitMsg('');
            setSaveOpen(true);
          }}
          style={{
            font: "600 11px 'IBM Plex Sans',sans-serif",
            color: '#101216',
            background: '#6ab8e8',
            padding: '5px 12px',
            borderRadius: 5,
            border: 'none',
            cursor: state.dirty ? 'pointer' : 'default',
            opacity: !selected || !state.dirty ? 0.5 : 1,
          }}
        >
          Save mapping
        </button>
      </div>

      {!selected ? (
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 8 }}>
          {repoErr ? (
            <span style={{ font: "400 13px 'IBM Plex Sans',sans-serif", color: '#f2a4a4', maxWidth: 560, textAlign: 'center' }}>
              {repoErr}
            </span>
          ) : (
            <span style={{ font: "400 13px 'IBM Plex Sans',sans-serif", color: '#5b6270' }}>
              {entries === null ? 'Cloning mapping repository…' : 'Select a mapping to edit'}
            </span>
          )}
        </div>
      ) : (
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
      )}

      <Modal opened={discardOpen} onClose={() => setDiscardOpen(false)} title="Discard changes" centered>
        <div style={{ font: "400 13px 'IBM Plex Sans',sans-serif", color: '#8b93a3', marginBottom: 16 }}>
          All unsaved changes to <strong>{selected?.name}</strong> will be lost. This cannot be undone.
        </div>
        <Button
          fullWidth
          color="red"
          onClick={() => {
            const sv = JSON.parse(savedRef.current);
            if (selected) localStorage.removeItem(DRAFT_PREFIX + selected.path);
            api.patch({ mapping: sv.mapping, virtual: sv.virtual, dirty: false, drafts: {}, vJsonEditing: false });
            setDiscardOpen(false);
          }}
        >
          Discard
        </Button>
      </Modal>

      <Modal opened={saveOpen} onClose={() => !saving && setSaveOpen(false)} title="Commit & push mapping" centered>
        <TextInput
          label="Your name"
          placeholder="Jane Doe"
          value={commitName}
          onChange={(e) => setCommitName(e.currentTarget.value)}
          required
          data-autofocus={!commitName}
        />
        <Textarea
          label="Describe your changes"
          placeholder="What changed and why — becomes the commit message"
          value={commitMsg}
          onChange={(e) => setCommitMsg(e.currentTarget.value)}
          required
          mt="sm"
          minRows={3}
          autosize
          data-autofocus={!!commitName}
        />
        {saveErr && (
          <div style={{ color: '#f2a4a4', font: "400 12px 'IBM Plex Sans',sans-serif", marginTop: 10 }}>{saveErr}</div>
        )}
        <Button fullWidth mt="md" loading={saving} disabled={!commitName.trim() || !commitMsg.trim()} onClick={doSave}>
          Commit &amp; push
        </Button>
      </Modal>

      <datalist id="mfields">
        {allFieldOpts.map((f) => (
          <option key={f} value={f} />
        ))}
      </datalist>
    </div>
  );
}
