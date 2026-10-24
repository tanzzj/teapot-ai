package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.audit.AuditService;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.WorkspaceMapper;
import com.teamer.teapot.ai.core.model.WorkspaceDO;
import com.teamer.teapot.ai.core.model.WorkspaceMemberDO;
import com.teamer.teapot.ai.core.model.dto.WorkspaceCreateRequest;
import com.teamer.teapot.ai.core.model.dto.WorkspaceUpdateRequest;
import com.teamer.teapot.ai.core.model.vo.WorkspaceMemberVO;
import com.teamer.teapot.ai.core.model.vo.WorkspaceVO;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import com.teamer.teapot.ai.rbac.dao.UserMapper;
import com.teamer.teapot.ai.rbac.model.TeapotUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 业务空间（SPEC §28.3/§28.4）：可见空间集合、建空间、成员授权、跨空间隔离闸口。
 * 空间级隔离是 Service 层数据作用域职责（RbacAccessFilter 只认 role→URI，不识别 workspace）。
 */
@Service
public class WorkspaceService {

    /** 个人空间编码前缀（SPEC §28.3，workspace_code = personal:{userId}） */
    public static final String PERSONAL_PREFIX = "personal:";
    private static final String PERSONAL_NAME = "个人空间";
    private static final String TYPE_PERSONAL = "personal";
    private static final String TYPE_TEAM = "team";

    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;
    private final TeapotAiProperties properties;
    private final AuditService auditService;

    public WorkspaceService(WorkspaceMapper workspaceMapper, UserMapper userMapper,
                            TeapotAiProperties properties, AuditService auditService) {
        this.workspaceMapper = workspaceMapper;
        this.userMapper = userMapper;
        this.properties = properties;
        this.auditService = auditService;
    }

    /** 系统白名单用户可见并管理全部空间（SPEC §28.7） */
    public boolean isWhitelisted(String userId) {
        List<String> whitelist = properties.getWorkspace().getWhitelist();
        return whitelist != null && whitelist.contains(userId);
    }

    /** 当前用户可见空间集合（SPEC §28.3）：个人空间（含懒创建前的合成项）+ 成员空间 + 白名单全量 */
    public List<WorkspaceVO> listVisible() {
        String uid = currentUserId();
        List<WorkspaceVO> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        WorkspaceDO personal = workspaceMapper.selectByCode(PERSONAL_PREFIX + uid);
        if (personal != null) {
            seen.add(personal.getId());
            result.add(toVO(personal, "owner", PERSONAL_NAME));
        } else {
            // 懒创建：个人空间尚未落库，仍以合成项（id=null）下发为默认（SPEC §28.3）
            result.add(WorkspaceVO.of(null, PERSONAL_NAME, PERSONAL_PREFIX + uid, TYPE_PERSONAL, "owner", null));
        }

        for (WorkspaceDO w : workspaceMapper.selectByUser(uid)) {
            if (TYPE_PERSONAL.equals(w.getWorkspaceType()) || !seen.add(w.getId())) {
                continue;
            }
            result.add(toVO(w, memberRoleOr(w, uid, "member"), w.getWorkspaceName()));
        }

        if (isWhitelisted(uid)) {
            for (WorkspaceDO w : workspaceMapper.selectAllEnabled()) {
                if (!seen.add(w.getId())) {
                    continue;
                }
                result.add(toVO(w, "owner", w.getWorkspaceName()));
            }
        }
        return result;
    }

    /**
     * 全部启用团队空间（仅 admin，SPEC §28.11）：供模型「开放空间」配置选择。
     * 个人空间不逐个下发——模型对个人空间的开放用全局 0 桶统一表达。
     */
    public List<WorkspaceVO> listAllEnabledForAdmin() {
        requireAdmin();
        List<WorkspaceVO> result = new ArrayList<>();
        for (WorkspaceDO w : workspaceMapper.selectAllEnabled()) {
            if (TYPE_PERSONAL.equals(w.getWorkspaceType())) {
                continue;
            }
            result.add(WorkspaceVO.of(w.getId(), w.getWorkspaceName(), w.getWorkspaceCode(),
                    w.getWorkspaceType(), "owner", w.getDescription()));
        }
        return result;
    }

    /** 建团队空间（SPEC §28.4）：编码唯一校验 + creator 作 owner + 批量加成员（加成员即授权） */
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceDO create(WorkspaceCreateRequest request) {
        String uid = currentUserId();
        String code = request.getWorkspaceCode().trim();
        if (workspaceMapper.selectByCode(code) != null) {
            throw new BizException("空间编码已存在：" + code);
        }
        WorkspaceDO w = new WorkspaceDO();
        w.setWorkspaceName(request.getWorkspaceName().trim());
        w.setWorkspaceCode(code);
        w.setWorkspaceType(TYPE_TEAM);
        w.setDescription(request.getDescription());
        w.setStatus(1);
        w.setCreator(uid);
        workspaceMapper.insert(w);
        insertMemberIfAbsent(w.getId(), uid, "owner", uid);
        if (request.getMemberUserIds() != null) {
            Set<String> distinct = new HashSet<>(request.getMemberUserIds());
            for (String memberUid : distinct) {
                if (memberUid == null || memberUid.isBlank() || memberUid.equals(uid)) {
                    continue;
                }
                requireUserExists(memberUid.trim());
                insertMemberIfAbsent(w.getId(), memberUid.trim(), "member", uid);
            }
        }
        auditService.log("workspace.create", code, "id=" + w.getId());
        return w;
    }

    /**
     * 编辑空间（SPEC §28.12）：改名/描述 + 成员集合整体同步，仅 owner 或白名单可操作；
     * 空间编码与类型不可改，个人空间不进本入口（名称由系统固定）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceDO update(Long workspaceId, WorkspaceUpdateRequest request) {
        WorkspaceDO w = requireManager(workspaceId);
        if (TYPE_PERSONAL.equals(w.getWorkspaceType())) {
            throw new BizException("个人空间不可编辑");
        }
        if (request.getWorkspaceName() != null && !request.getWorkspaceName().isBlank()) {
            w.setWorkspaceName(request.getWorkspaceName().trim());
        }
        if (request.getDescription() != null) {
            w.setDescription(request.getDescription());
        }
        workspaceMapper.update(w);
        if (request.getMemberUserIds() != null) {
            syncMembers(w.getId(), request.getMemberUserIds());
        }
        auditService.log("workspace.update", String.valueOf(workspaceId), null);
        return workspaceMapper.selectById(workspaceId);
    }

    /** 成员集合与期望全集对齐：期望里没有的成员删掉（owner 行永不删），新增的按 member 落库 */
    private void syncMembers(Long workspaceId, List<String> memberUserIds) {
        String operator = currentUserId();
        Set<String> desired = new HashSet<>();
        for (String uid : memberUserIds) {
            if (uid == null || uid.isBlank()) {
                continue;
            }
            requireUserExists(uid.trim());
            desired.add(uid.trim());
        }
        for (WorkspaceMemberVO current : workspaceMapper.selectMembersByWorkspace(workspaceId)) {
            String uid = current.getUserId();
            if ("owner".equals(current.getMemberRole()) || desired.remove(uid)) {
                continue;
            }
            workspaceMapper.deleteMember(workspaceId, uid);
        }
        for (String uid : desired) {
            insertMemberIfAbsent(workspaceId, uid, "member", operator);
        }
    }

    /** 个人空间 id（读路径用，SPEC §28.3）：未创建返回 null，绝不写库（读不触发懒建） */
    public Long personalWorkspaceIdOrNull(String userId) {
        WorkspaceDO personal = workspaceMapper.selectByCode(PERSONAL_PREFIX + userId);
        return personal == null ? null : personal.getId();
    }

    /** 当前用户个人空间，缺则懒创建（SPEC §28.3/§28.4，首个 Agent 触发的落点） */
    @Transactional(rollbackFor = Exception.class)
    public Long ensurePersonalWorkspace(String userId) {
        WorkspaceDO existing = workspaceMapper.selectByCode(PERSONAL_PREFIX + userId);
        if (existing != null) {
            return existing.getId();
        }
        WorkspaceDO w = new WorkspaceDO();
        w.setWorkspaceName(PERSONAL_NAME);
        w.setWorkspaceCode(PERSONAL_PREFIX + userId);
        w.setWorkspaceType(TYPE_PERSONAL);
        w.setStatus(1);
        w.setCreator(userId);
        workspaceMapper.insert(w);
        insertMemberIfAbsent(w.getId(), userId, "owner", userId);
        return w.getId();
    }

    /**
     * 跨空间隔离闸口（SPEC §28.3 需求 2-2）：白名单放行；否则必须为该空间成员，越权抛 403。
     * workspaceId 为 null 视为非法（个人空间请走 ensurePersonalWorkspace）。
     */
    public WorkspaceDO requireMember(Long workspaceId) {
        if (workspaceId == null) {
            throw new BizException(Result.CODE_FORBIDDEN, "未指定空间");
        }
        String uid = currentUserId();
        WorkspaceDO w = workspaceMapper.selectById(workspaceId);
        if (w == null || !Integer.valueOf(1).equals(w.getStatus())) {
            throw new BizException("空间不存在或已停用：" + workspaceId);
        }
        if (isWhitelisted(uid)) {
            return w;
        }
        if (workspaceMapper.countMember(workspaceId, uid) == 0) {
            throw new BizException(Result.CODE_FORBIDDEN, "无权限访问该空间");
        }
        return w;
    }

    /** Agent 建/移空间的目标解析（SPEC §28.4）：null→个人空间（懒创建）；否则校验成员后返回原 id */
    public Long resolveTargetWorkspace(Long workspaceId) {
        if (workspaceId == null) {
            return ensurePersonalWorkspace(currentUserId());
        }
        requireMember(workspaceId);
        return workspaceId;
    }

    /**
     * 模型授权空间桶（SPEC §28.11）：个人空间/未归属 → 全局 0 桶；团队空间 → 其自身 id。
     * 用于把 Agent 归属空间映射到 teapot_workspace_model 的授权桶做校验。
     */
    public long modelBucketForWorkspace(Long workspaceId) {
        if (workspaceId == null) {
            return 0L;
        }
        WorkspaceDO w = workspaceMapper.selectById(workspaceId);
        if (w == null || TYPE_PERSONAL.equals(w.getWorkspaceType())) {
            return 0L;
        }
        return workspaceId;
    }

    /** 空间成员列表（SPEC §28.4）：先过跨空间隔离，非成员不可查他人空间的成员 */
    public List<WorkspaceMemberVO> listMembers(Long workspaceId) {
        requireMember(workspaceId);
        return workspaceMapper.selectMembersByWorkspace(workspaceId);
    }

    /** 加成员（SPEC §28.4，加成员即授权）：仅 owner 或白名单可操作 */
    @Transactional(rollbackFor = Exception.class)
    public void addMember(Long workspaceId, String userId) {
        requireManager(workspaceId);
        String uid = userId == null ? "" : userId.trim();
        requireUserExists(uid);
        if (workspaceMapper.countMember(workspaceId, uid) > 0) {
            return;
        }
        insertMemberIfAbsent(workspaceId, uid, "member", currentUserId());
        auditService.log("workspace.addMember", String.valueOf(workspaceId), "user=" + uid);
    }

    /** 移成员（仅 owner/白名单；不可移除 owner 自己以外的 owner 视白名单而定，MVP 简化：不可移除任何 owner） */
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long workspaceId, String userId) {
        requireManager(workspaceId);
        String uid = userId == null ? "" : userId.trim();
        String role = workspaceMapper.selectMemberRole(workspaceId, uid);
        if ("owner".equals(role) && !isWhitelisted(currentUserId())) {
            throw new BizException("不能移除空间创建人");
        }
        workspaceMapper.deleteMember(workspaceId, uid);
        auditService.log("workspace.removeMember", String.valueOf(workspaceId), "user=" + uid);
    }

    /** 软删空间（仅 owner/白名单）：本 MVP 仅停用，不做 Agent 归属清理（待确认项 §28.10-3） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long workspaceId) {
        WorkspaceDO w = requireManager(workspaceId);
        if (TYPE_PERSONAL.equals(w.getWorkspaceType())) {
            throw new BizException("个人空间不可删除");
        }
        workspaceMapper.softDelete(workspaceId);
        auditService.log("workspace.delete", String.valueOf(workspaceId), null);
    }

    private WorkspaceDO requireManager(Long workspaceId) {
        WorkspaceDO w = requireMember(workspaceId);
        if (isWhitelisted(currentUserId())) {
            return w;
        }
        String role = workspaceMapper.selectMemberRole(workspaceId, currentUserId());
        if (!"owner".equals(role)) {
            throw new BizException(Result.CODE_FORBIDDEN, "仅空间创建人可管理成员");
        }
        return w;
    }

    private void insertMemberIfAbsent(Long workspaceId, String userId, String role, String operator) {
        if (workspaceMapper.countMember(workspaceId, userId) > 0) {
            return;
        }
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setCreator(operator);
        workspaceMapper.insertMember(member);
    }

    private void requireUserExists(String userId) {
        if (userId == null || userId.isBlank() || userMapper.selectByUserId(userId) == null) {
            throw new BizException("用户不存在：" + userId);
        }
    }

    private String memberRoleOr(WorkspaceDO w, String uid, String fallback) {
        String role = workspaceMapper.selectMemberRole(w.getId(), uid);
        return role != null ? role : fallback;
    }

    private WorkspaceVO toVO(WorkspaceDO w, String myRole, String displayName) {
        return WorkspaceVO.of(w.getId(), displayName, w.getWorkspaceCode(),
                w.getWorkspaceType(), myRole, w.getDescription());
    }

    private String currentUserId() {
        String uid = ContextUtil.currentUserId();
        if (uid == null || uid.isBlank()) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "未登录");
        }
        return uid;
    }

    /** 仅 admin（系统级配置读取，SPEC §28.11）；RbacAccessFilter 只按 role→URI 粗放行，此处兜底 */
    private void requireAdmin() {
        TeapotUser user = ContextUtil.getUserFromContext();
        String roles = user == null ? null : user.getRoles();
        if (roles == null || !Arrays.asList(roles.split(",")).contains("admin")) {
            throw new BizException(Result.CODE_FORBIDDEN, "仅管理员可操作");
        }
    }
}
