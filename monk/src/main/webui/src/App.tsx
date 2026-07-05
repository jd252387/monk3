import { useEffect, useState } from 'react';
import { SegmentedControl } from '@mantine/core';
import MappingEditor from './mapping-editor/MappingEditor';
import QueryConsole from './QueryConsole';

type View = 'query' | 'mappings';

const VIEW_KEY = 'monk.view';

export default function App() {
  const [view, setView] = useState<View>(() =>
    localStorage.getItem(VIEW_KEY) === 'mappings' ? 'mappings' : 'query',
  );

  useEffect(() => {
    localStorage.setItem(VIEW_KEY, view);
  }, [view]);

  const nav = (
    <SegmentedControl
      size="xs"
      value={view}
      onChange={(v) => setView(v as View)}
      data={[
        { label: 'Query console', value: 'query' },
        { label: 'Mapping editor', value: 'mappings' },
      ]}
    />
  );

  return view === 'query' ? <QueryConsole nav={nav} /> : <MappingEditor nav={nav} />;
}
