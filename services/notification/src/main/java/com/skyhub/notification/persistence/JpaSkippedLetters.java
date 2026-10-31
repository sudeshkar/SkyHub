package com.skyhub.notification.persistence;

import com.skyhub.notification.application.SkippedLetters;
import com.skyhub.notification.domain.SkippedLetter;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Adapter: the skip list is a table (ADR-0037). Each method is one short transaction and one statement, or two at most. */
@Component
class JpaSkippedLetters implements SkippedLetters {

    private final SkippedLetterRepository letters;

    JpaSkippedLetters(SkippedLetterRepository letters) {
        this.letters = letters;
    }

    @Override
    @Transactional
    public void skip(int partition, long offset, String reason, Instant at) {
        if (!letters.existsByPartitionAndOffset(partition, offset)) {
            letters.save(SkippedLetter.of(partition, offset, reason, at));
        }
    }

    @Override
    @Transactional
    public boolean unskip(int partition, long offset) {
        return letters.deleteOne(partition, offset) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> skippedIn(int partition, long fromOffset, long toOffset) {
        return new HashSet<>(letters.offsetsBetween(partition, fromOffset, toOffset));
    }

    @Override
    @Transactional
    public void forget(int partition, Collection<Long> offsets) {
        if (!offsets.isEmpty()) {
            letters.deleteAll(partition, offsets);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Skip> all() {
        return letters.findAll().stream()
                .map(s -> new Skip(s.getPartition(), s.getOffset(), s.getReason(), s.getSkippedAt()))
                .sorted(java.util.Comparator.comparingInt(Skip::partition).thenComparingLong(Skip::offset)).toList();
    }
}
