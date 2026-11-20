package com.wenghong.notifyflow.identity.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wenghong.notifyflow.identity.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    @Query("""
                select u from AppUser u
                where lower(u.email) = lower(:email)
            """)
    Optional<AppUser> findByEmail(@Param("email") String email);
}
