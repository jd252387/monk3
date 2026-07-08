import type { Change } from './model';
import { BLOCK_HEADER_STYLE } from './TreePane';

const KIND_META: Record<Change['kind'], { glyph: string; color: string }> = {
  added: { glyph: '+', color: '#7ee8a2' },
  removed: { glyph: '−', color: '#e07070' },
  changed: { glyph: '~', color: '#e8b04a' },
};

function Row({ c }: { c: Change }) {
  const { glyph, color } = KIND_META[c.kind];
  return (
    <div style={{ padding: '3px 12px' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, font: "500 12px 'IBM Plex Mono',monospace" }}>
        <span style={{ color, width: 8, textAlign: 'center' }}>{glyph}</span>
        <span style={{ color: '#d7dce6' }}>{c.path}</span>
        <span style={{ flex: 1 }} />
        <span style={{ font: "500 9px 'IBM Plex Mono',monospace", letterSpacing: '.05em', color }}>{c.kind}</span>
      </div>
      {c.keys.map((k) => (
        <div
          key={k.key}
          style={{ padding: '1px 0 1px 24px', font: "400 11px 'IBM Plex Mono',monospace", color: '#79818f' }}
        >
          <span style={{ color: '#9aa2b1' }}>{k.key}</span>: <span style={{ color: '#6b7280' }}>{k.from}</span>
          {' → '}
          <span style={{ color: '#a5d6a7' }}>{k.to}</span>
        </div>
      ))}
    </div>
  );
}

export default function ChangesPane({ changes }: { changes: Change[] }) {
  const phys = changes.filter((c) => c.scope === 'physical');
  const virt = changes.filter((c) => c.scope === 'virtual');

  return (
    <div style={{ flex: 1, overflow: 'auto', padding: '8px 0', minHeight: 0 }}>
      {changes.length === 0 ? (
        <div style={{ padding: '24px 14px', textAlign: 'center', font: "400 12px 'IBM Plex Sans',sans-serif", color: '#5b6270' }}>
          No changes since last commit
        </div>
      ) : (
        <>
          {phys.length > 0 && (
            <>
              <div style={BLOCK_HEADER_STYLE}>PHYSICAL</div>
              {phys.map((c) => (
                <Row key={c.path} c={c} />
              ))}
            </>
          )}
          {virt.length > 0 && (
            <>
              <div style={BLOCK_HEADER_STYLE}>VIRTUAL</div>
              {virt.map((c) => (
                <Row key={c.path} c={c} />
              ))}
            </>
          )}
        </>
      )}
    </div>
  );
}
