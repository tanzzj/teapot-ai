import type { A2uiComponent } from './envelope';
import { extractObjects, str, strList } from './envelope';

/**
 * ask_user_question（表单档）挂起时信封只存在于 interrupt message（工具无 TOOL_CALL_RESULT）；
 * 历史回放拿不到 message，则从工具入参（声明式表单 spec）前端重建同款表单
 * ——与后端 AskUserQuestionTool.toFormComponents 一一对应。
 */

export interface AskFormFrame {
  title?: string;
  description?: string;
  submitText?: string;
}

export function extractQuestions(args: string): Record<string, unknown>[] {
  return extractObjects(args, 'questions').filter((q) => !!str(q.id) && !!str(q.question));
}

/** 表单级字段（title/description/submitText）：整段可解析时取 JSON，流式中途退化成正则取串 */
export function extractFormFrame(args: string): AskFormFrame {
  return {
    title: extractStringArg(args, 'title'),
    description: extractStringArg(args, 'description'),
    submitText: extractStringArg(args, 'submitText'),
  };
}

function extractStringArg(src: string, key: string): string | undefined {
  if (!src) return undefined;
  try {
    return str((JSON.parse(src) as Record<string, unknown>)?.[key]);
  } catch {
    // 流式未完成：直接找已闭合的那个字符串字面量
  }
  const m = new RegExp(`"${key}"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"`).exec(src);
  if (!m) return undefined;
  try {
    return JSON.parse(`"${m[1]}"`) as string;
  } catch {
    return undefined;
  }
}

export function questionsToComponents(
  questions: Record<string, unknown>[],
  frame: AskFormFrame = {},
): A2uiComponent[] {
  const components: A2uiComponent[] = [];
  if (frame.title) {
    components.push({ id: 'a2ui_title', component: 'Heading', props: { text: frame.title } });
  }
  if (frame.description) {
    components.push({ id: 'a2ui_desc', component: 'Text', props: { text: frame.description } });
  }
  for (const q of questions) {
    const id = str(q.id)!;
    const type = str(q.type) ?? 'text';
    const options = strList(q.options);
    const placeholder = str(q.placeholder);
    const props: Record<string, unknown> = { name: id, label: str(q.question) };
    if (q.required === true) props.required = true;
    let component = 'TextInput';
    if (type === 'select') {
      component = 'Select';
      props.options = options;
    } else if (type === 'multi_select') {
      component = 'Select';
      props.options = options;
      props.multiple = true;
    } else if (type === 'radio') {
      component = 'RadioGroup';
      props.options = options;
    } else if (type === 'checkbox') {
      component = 'Checkbox';
    } else if (type === 'textarea') {
      component = 'TextArea';
    } else if (type === 'confirm') {
      component = 'RadioGroup';
      props.options = ['yes', 'no'];
    }
    if (component === 'TextInput' && placeholder) props.placeholder = placeholder;
    components.push({ id: `q_${id}`, component, props });
  }
  components.push({
    id: 'a2ui_submit',
    component: 'Button',
    props: { text: frame.submitText || 'Submit', action: 'submit' },
  });
  return components;
}
