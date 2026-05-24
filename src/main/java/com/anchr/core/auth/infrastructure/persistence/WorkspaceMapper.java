package com.anchr.core.auth.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface WorkspaceMapper {

    int insertWorkspace(WorkspaceRecord record);

    int insertMember(WorkspaceMemberRecord record);

    int updateMemberRole(@Param("workspaceId") String workspaceId,
                         @Param("userId") String userId,
                         @Param("role") String role,
                         @Param("updatedBy") String updatedBy,
                         @Param("updatedAt") LocalDateTime updatedAt);

    int removeMember(@Param("workspaceId") String workspaceId,
                     @Param("userId") String userId,
                     @Param("updatedBy") String updatedBy,
                     @Param("updatedAt") LocalDateTime updatedAt);

    List<WorkspaceRecord> listByUser(@Param("userId") String userId);

    List<WorkspaceMemberRecord> listMembers(@Param("workspaceId") String workspaceId);

    Optional<WorkspaceMemberRecord> findMember(@Param("workspaceId") String workspaceId,
                                               @Param("userId") String userId);
}
