import type { A2uiComponent } from './envelope';
import { num } from './envelope';

/**
 * A2UI 流式排版（catalog 约定）：components 是扁平列表，
 * Row 吞并后续组件作为子项直到 Column/Divider；Column 收尾当前 Row 并接管后续组件；
 * Divider 收尾一切并自身独占一行。
 */

export type FlowBlock =
  | { kind: 'component'; comp: A2uiComponent }
  | { kind: 'row'; gap: number; children: A2uiComponent[] }
  | { kind: 'column'; gap: number; children: A2uiComponent[] };

export function layoutFlow(components: A2uiComponent[]): FlowBlock[] {
  const blocks: FlowBlock[] = [];
  let open: { kind: 'row' | 'column'; gap: number; children: A2uiComponent[] } | null = null;
  const close = () => {
    if (open) {
      blocks.push(open);
      open = null;
    }
  };
  for (const comp of components) {
    if (comp.component === 'Row' || comp.component === 'Column') {
      close();
      const isRow = comp.component === 'Row';
      open = {
        kind: isRow ? 'row' : 'column',
        gap: num(comp.props?.gap, isRow ? 8 : 6),
        children: [],
      };
    } else if (comp.component === 'Divider') {
      close();
      blocks.push({ kind: 'component', comp });
    } else if (open) {
      open.children.push(comp);
    } else {
      blocks.push({ kind: 'component', comp });
    }
  }
  close();
  return blocks;
}
