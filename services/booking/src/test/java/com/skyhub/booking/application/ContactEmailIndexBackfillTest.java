package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.booking.application.UnindexedContacts.Contact;
import com.skyhub.booking.domain.ContactEmail;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ContactEmailIndexBackfillTest {

    /** Holds the rows in id order, the way the database would, and counts how it was asked. */
    private static class FakeStore implements UnindexedContacts {
        final Map<Long, String> indexed = new TreeMap<>();
        final Map<Long, ContactEmail> unindexed = new TreeMap<>();
        final List<Integer> limitsAsked = new ArrayList<>();

        @Override
        public List<Contact> after(long id, int limit) {
            limitsAsked.add(limit);
            return unindexed.entrySet().stream().filter(e -> e.getKey() > id).limit(limit)
                    .map(e -> new Contact(e.getKey(), e.getValue())).toList();
        }

        @Override
        public boolean setIndex(long id, String index) {
            if (unindexed.remove(id) == null) {
                return false;          // a booking made meanwhile already had its index
            }
            indexed.put(id, index);
            return true;
        }
    }

    private final FakeStore store = new FakeStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EmailIndex index = email -> "idx:" + email.strip().toLowerCase();

    private ContactEmailIndexBackfill backfill(int batchSize) {
        return new ContactEmailIndexBackfill(store, index, new MyBookingsMetrics(registry), batchSize);
    }

    private void addRows(int count) {
        IntStream.rangeClosed(1, count).forEach(i -> store.unindexed.put((long) i, new ContactEmail("p" + i + "@example.com")));
    }

    @Test
    void should_index_every_old_booking_from_its_address() {
        addRows(5);

        int done = backfill(500).run();

        assertThat(done).isEqualTo(5);
        assertThat(store.unindexed).isEmpty();
        assertThat(store.indexed).containsEntry(3L, "idx:p3@example.com").hasSize(5);
    }

    @Test
    void should_work_in_bounded_batches_so_memory_does_not_grow_with_the_table() {
        addRows(1_200);

        int done = backfill(500).run();

        assertThat(done).isEqualTo(1_200);
        assertThat(store.limitsAsked).containsOnly(500).hasSize(3);    // 500 + 500 + 200: a short batch is the last
    }

    @Test
    void should_do_nothing_when_every_booking_already_has_an_index() {
        int done = backfill(500).run();

        assertThat(done).isZero();
        assertThat(store.limitsAsked).hasSize(1);
    }

    @Test
    void should_not_count_a_booking_that_was_indexed_while_it_ran() {
        addRows(3);
        var racing = new FakeStore() {
            @Override
            public List<Contact> after(long id, int limit) {
                List<Contact> found = super.after(id, limit);
                unindexed.remove(2L);              // a new booking path indexed row 2 after we read it
                indexed.put(2L, "set-by-the-booking");
                return found;
            }
        };
        racing.unindexed.putAll(store.unindexed);

        int done = new ContactEmailIndexBackfill(racing, index, new MyBookingsMetrics(registry), 500).run();

        assertThat(done).isEqualTo(2);
        assertThat(racing.indexed).containsEntry(2L, "set-by-the-booking");
    }

    @Test
    void should_skip_an_address_it_cannot_read_and_carry_on_with_the_rest() {
        addRows(3);
        var unreadable = new FakeStore() {
            @Override
            public List<Contact> after(long id, int limit) {
                return super.after(id, limit).stream().map(c -> c.id() == 2 ? new Contact(2, null) : c).toList();
            }
        };
        unreadable.unindexed.putAll(store.unindexed);

        int done = new ContactEmailIndexBackfill(unreadable, index, new MyBookingsMetrics(registry), 2).run();

        assertThat(done).isEqualTo(2);
        assertThat(unreadable.indexed).containsOnlyKeys(1L, 3L);
        assertThat(unreadable.unindexed).containsOnlyKeys(2L);    // left for the next start
    }

    @Test
    void should_count_what_it_indexed() {
        addRows(4);

        backfill(2).run();

        assertThat(registry.find("skyhub.contact.index.backfilled").counter().count()).isEqualTo(4);
    }

    @Test
    void should_count_the_bookings_it_could_not_read_once_per_run_not_once_per_row() {
        addRows(1);
        var withUnreadable = new FakeStore() {
            @Override
            public List<Contact> after(long id, int limit) {
                return super.after(id, limit).stream().map(c -> new Contact(c.id(), null)).toList();
            }
        };
        withUnreadable.unindexed.put(1L, new ContactEmail("p1@example.com"));
        withUnreadable.unindexed.put(2L, new ContactEmail("p2@example.com"));
        withUnreadable.unindexed.put(3L, new ContactEmail("p3@example.com"));

        int done = new ContactEmailIndexBackfill(withUnreadable, index, new MyBookingsMetrics(registry), 500).run();

        assertThat(done).isZero();
        assertThat(registry.find("skyhub.contact.index.unreadable").counter().count()).isEqualTo(3);   // one number for the run, not a line per row
    }

    @Test
    void should_report_nothing_unreadable_when_every_address_could_be_read() {
        addRows(3);

        backfill(500).run();

        assertThat(registry.find("skyhub.contact.index.unreadable").counter()).isNull();
    }
}
