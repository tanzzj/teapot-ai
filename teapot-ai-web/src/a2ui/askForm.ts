import type { A2uiComponent } from './envelope';
import { extractObjects, str, strList } from './envelope';

/**
 * ask_user_question（表单档）挂起时信封只存在于 interrupt message（工具无 TOOL_CALL_RESULT）；
 * 历史回放拿不到 message，则从工具入参 questions 前端重建同款表单
 * ——与后端 AskUserQuestionTool.toFormComponents 一一对应。
 */

export function extractQuestions(args: string): Record<string, unknown>[] {
  return extractObjects(args, 'questions').filter((q) => !!str(q.id) && !!str(q.question));
}

export function questionsToComponents(questions: Record<string, unknown>[]): A2uiComponent[] {
  const components: A2uiComponent[] = [];
  for (const q of questions) {
    const id = str(q.id)!;
    const type = str(q.type) ?? 'text';
    const options = strList(q.options);
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
    } else if (type === 'confirm') {
      component = 'RadioGroup';
      props.options = ['yes', 'no'];
    }
    components.push({ id: `q_${id}`, component, props });
  }
  components.push({
    id: 'a2ui_submit',
    component: 'Button',
    props: { text: 'Submit', action: 'submit' },
  });
  return components;
}
