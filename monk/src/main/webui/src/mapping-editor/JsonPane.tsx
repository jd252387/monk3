import { useEffect, useRef, type ReactNode } from 'react';
import { PH, type Sel } from './model';

const LINE_HEIGHT = 11 * 1.75; // matches the pane's font: 11px/1.75

interface DocLines {
  lines: string[];
  hlS: number;
  hlE: number;
}

function buildDocLines(doc: Record<string, Record<string, unknown>>, schema: string, sel: Sel | null): DocLines {
  const L: string[] = [];
  let hlS = -1;
  let hlE = -1;
  L.push('{');
  L.push('  "$schema": "' + schema + '",');
  const blocks = Object.keys(doc);
  blocks.forEach((b, bi) => {
    L.push('  "' + b + '": {');
    const keys = Object.keys(doc[b] || {});
    keys.forEach((k, ki) => {
      const seg = JSON.stringify(doc[b][k], null, 2).split('\n');
      const start = L.length;
      seg.forEach((ln, li) => {
        L.push('    ' + (li === 0 ? '"' + k + '": ' : '') + ln);
      });
      if (ki < keys.length - 1) L[L.length - 1] += ',';
      if (sel && b === sel.block && k === sel.field) {
        hlS = start;
        hlE = L.length - 1;
      }
    });
    L.push('  }' + (bi < blocks.length - 1 ? ',' : ''));
  });
  L.push('}');
  return { lines: L, hlS, hlE };
}

/** Syntax-highlight strings / numbers / booleans within one line. */
function tok(s: string): ReactNode[] {
  const out: ReactNode[] = [];
  const re = /("(?:[^"\\]|\\.)*")|(-?\d+(?:\.\d+)?)|\b(true|false|null)\b/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let k = 0;
  while ((m = re.exec(s))) {
    if (m.index > last) out.push(s.slice(last, m.index));
    if (m[1]) {
      const isPh = m[1].includes(PH);
      out.push(
        <span
          key={k++}
          style={{
            color: isPh ? '#e88ab8' : '#a5d6a7',
            background: isPh ? 'rgba(232,138,184,.13)' : 'transparent',
            borderRadius: 3,
          }}
        >
          {m[1]}
        </span>,
      );
    } else if (m[2]) {
      out.push(
        <span key={k++} style={{ color: '#e8b04a' }}>
          {m[2]}
        </span>,
      );
    } else {
      out.push(
        <span key={k++} style={{ color: '#7ee8a2' }}>
          {m[3]}
        </span>,
      );
    }
    last = re.lastIndex;
  }
  if (last < s.length) out.push(s.slice(last));
  return out;
}

export default function JsonPane({
  doc,
  schema,
  sel,
  fileName,
}: {
  doc: Record<string, Record<string, unknown>>;
  schema: string;
  sel: Sel | null;
  fileName: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const { lines, hlS, hlE } = buildDocLines(doc, schema, sel);

  // Scroll to the selected field only when the selection changes, not on every edit.
  const selKey = fileName + '|' + (sel ? sel.block + '.' + sel.field : '');
  const hlStartRef = useRef(hlS);
  hlStartRef.current = hlS;
  useEffect(() => {
    if (ref.current && hlStartRef.current >= 0) {
      ref.current.scrollTop = Math.max(0, (hlStartRef.current - 4) * LINE_HEIGHT);
    }
  }, [selKey]);

  return (
    <div
      style={{
        flex: 1,
        background: '#12141a',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', padding: '10px 14px', borderBottom: '1px solid #2a2e37', flex: 'none' }}>
        <span style={{ font: "600 10px 'IBM Plex Mono',monospace", letterSpacing: '.06em', color: '#79818f' }}>LIVE JSON</span>
        <span style={{ font: "400 10px 'IBM Plex Mono',monospace", color: '#4a5060', marginLeft: 8 }}>{fileName}</span>
        <span style={{ flex: 1 }} />
        <span style={{ font: "400 10px 'IBM Plex Sans',sans-serif", color: '#5b6270' }}>follows selection</span>
      </div>
      <div
        ref={ref}
        style={{
          flex: 1,
          overflow: 'auto',
          padding: '12px 0',
          font: "400 11px/1.75 'IBM Plex Mono',monospace",
          whiteSpace: 'pre',
          minHeight: 0,
        }}
      >
        {lines.map((ln, i) => {
          const inHl = i >= hlS && i <= hlE;
          const m = ln.match(/^(\s*)("(?:[^"\\]|\\.)*")(:)(.*)$/);
          let kids: ReactNode[];
          if (m) {
            kids = [
              m[1],
              <span key="k" style={{ color: '#79b8ff' }}>
                {m[2]}
              </span>,
              ':',
              ...tok(m[4]),
            ];
          } else {
            kids = tok(ln);
          }
          if (kids.length === 0) kids = [' '];
          return (
            <div key={i} style={{ background: inHl ? 'rgba(106,184,232,.09)' : 'transparent', padding: '0 14px' }}>
              {kids}
            </div>
          );
        })}
      </div>
    </div>
  );
}
