package com.anchr.core.auth.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface UserAccountMapper {

    int insert(UserAccountRecord record);

    long count();

    Optional<UserAccountRecord> findByEmail(@Param("email") String email);

    Optional<UserAccountRecord> findById(@Param("id") String id);
}
