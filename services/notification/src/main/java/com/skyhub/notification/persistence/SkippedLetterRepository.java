package com.skyhub.notification.persistence;

import com.skyhub.notification.domain.SkippedLetter;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkippedLetterRepository extends JpaRepository<SkippedLetter, Long> {

    boolean existsByPartitionAndOffset(int partition, long offset);

    @Query("select s.offset from SkippedLetter s where s.partition = :partition and s.offset between :from and :to")
    List<Long> offsetsBetween(@Param("partition") int partition, @Param("from") long from, @Param("to") long to);

    @Modifying
    @Query("delete from SkippedLetter s where s.partition = :partition and s.offset = :offset")
    int deleteOne(@Param("partition") int partition, @Param("offset") long offset);

    @Modifying
    @Query("delete from SkippedLetter s where s.partition = :partition and s.offset in :offsets")
    int deleteAll(@Param("partition") int partition, @Param("offsets") Collection<Long> offsets);
}
