import { http, unwrap } from './http';
import type { Result, SkillDetail, SkillListItem, SkillSaveRequest } from '../types';

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
