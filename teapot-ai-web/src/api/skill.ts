import { http, unwrap } from './http';
import type {
  Result,
  SkillDetail,
  SkillGitStatus,
  SkillImportResult,
  SkillListItem,
  SkillOssStatus,
  SkillSaveRequest,
} from '../types';

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

/** zip 导入（双落点）：target=oss 写 OSS 对象（同名覆盖）；target=mysql 存平台库 */
export function skillImport(file: File, target: 'oss' | 'mysql') {
  const form = new FormData();
  form.append('file', file);
  form.append('target', target);
  return unwrap<SkillImportResult>(
    http.post<Result<SkillImportResult>>('/api/skill/import', form),
  );
}

/** 任意 Git 仓库导入：临时 clone 后按 zip 导入同款规则入库 */
export function skillImportFromGit(url: string, branch: string, target: 'oss' | 'mysql') {
  return unwrap<SkillImportResult>(
    http.post<Result<SkillImportResult>>('/api/skill/import/git', { url, branch, target }),
  );
}

/** OSS 来源状态 */
export function skillOssStatus() {
  return unwrap<SkillOssStatus>(http.get<Result<SkillOssStatus>>('/api/skill/oss/status'));
}

/** OSS 来源手动刷新缓存 */
export function skillOssRefresh() {
  return unwrap<SkillOssStatus>(http.post<Result<SkillOssStatus>>('/api/skill/oss/refresh'));
}
