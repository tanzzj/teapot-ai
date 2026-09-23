import { useMemo } from 'react';
import type { A2uiComponent } from './envelope';
import { bool, str } from './envelope';
import { layoutFlow } from './flowLayout';
import { A2uiWidget, type FormCtx } from './widgets';

/** surface 内的全部表单字段（含 Form 内联字段），用于必填校验与提交文本 */
export interface A2uiField {
  name: string;
  label: string;
  required: boolean;
}

export function collectFields(components: A2uiComponent[]): A2uiField[] {
  const fields: A2uiField[] = [];
  for (const comp of components) {
    if (comp.component === 'Form') {
      for (const f of (Array.isArray(comp.props?.fields) ? comp.props.fields : []) as A2uiField[]) {
        if (f?.name) {
          fields.push({ name: f.name, label: f.label ?? f.name, required: bool(f.required) });
        }
      }
    } else if (
      ['TextInput', 'TextArea', 'Select', 'Checkbox', 'RadioGroup', 'FileDrop'].includes(
        comp.component,
      )
    ) {
      const name = str(comp.props?.name);
      if (name) {
        fields.push({
          name,
          label: str(comp.props?.label) ?? name,
          required: bool(comp.props?.required),
        });
      }
    }
  }
  return fields;
}

/** 一个 surface 信封的渲染主体：流式排版 + 表单值绑定 */
export function A2uiSurfaceView({
  components,
  ctx,
  onSubmit,
}: {
  components: A2uiComponent[];
  ctx: FormCtx;
  onSubmit: () => void;
}) {
  const blocks = useMemo(() => layoutFlow(components), [components]);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {blocks.map((block, i) => {
        if (block.kind === 'component') {
          return (
            <A2uiWidget key={block.comp.id ?? i} comp={block.comp} ctx={ctx} onSubmit={onSubmit} />
          );
        }
        if (!block.children.length) return null;
        return (
          <div
            key={i}
            style={{
              display: 'flex',
              flexDirection: block.kind === 'row' ? 'row' : 'column',
              flexWrap: block.kind === 'row' ? 'wrap' : undefined,
              alignItems: block.kind === 'row' ? 'flex-start' : undefined,
              gap: block.gap,
            }}
          >
            {block.children.map((c, j) => (
              <div key={c.id ?? j} style={block.kind === 'row' ? { flex: '0 1 auto', minWidth: 0 } : { minWidth: 0 }}>
                <A2uiWidget comp={c} ctx={ctx} onSubmit={onSubmit} />
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
}
