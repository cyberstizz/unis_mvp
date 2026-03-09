package com.unis.repository;

import com.unis.entity.DmcaCounterNotice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DmcaCounterNoticeRepository extends JpaRepository<DmcaCounterNotice, UUID> {

    @Query("SELECT cn FROM DmcaCounterNotice cn WHERE cn.claim.claimId = :claimId")
    Optional<DmcaCounterNotice> findByClaimId(@Param("claimId") UUID claimId);

    @Query("SELECT cn FROM DmcaCounterNotice cn WHERE cn.respondentUser.userId = :userId ORDER BY cn.filedAt DESC")
    java.util.List<DmcaCounterNotice> findByRespondentUserId(@Param("userId") UUID userId);
}