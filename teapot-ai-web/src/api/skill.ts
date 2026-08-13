import { http, unwrap } from './http';
import type { Result, SkillDetail, SkillGitStatus, SkillListItem, SkillSaveRequest } from '../types';

export function skillList() {
  return unwrap<SkillListItem[]>(http.get<Result<SkillListItem[]>>('/api/skill/list'));
}

export function skillDetail(name: string) {
  return unwrap<SkillDetail>(http.get<Result<SkillDetail>>(`/api/skill/detail/${name}`));
}

export function skillSave(payload: SkillSaveRequest) {
  return unwrap<void>(http.post<Result<void>>('/api/skill/save', payload));
}

export function skillDelete(name: string) {
  return unwrap<void>(http.delete<Result<void>>(`/api/skill/delete/${name}`));
}

export function skillPreview(payload: SkillSaveRequest) {
  return unwrap<string>(http.post<Result<string>>('/api/skill/preview', payload));
}

/** Git 来源状态（SPEC §15.9） */
export function skillGitStatus() {
  return unwrap<SkillGitStatus>(http.get<Result<SkillGitStatus>>('/api/skill/git/status'));
}

/** Git 手动同步（developer，SPEC §15.9） */
export function skillGitSync() {
  return unwrap<SkillGitStatus>(http.post<Result<SkillGitStatus>>('/api/skill/git/sync'));
}
