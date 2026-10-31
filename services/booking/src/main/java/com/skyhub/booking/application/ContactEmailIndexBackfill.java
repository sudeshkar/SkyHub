package com.skyhub.booking.application;

import com.skyhub.booking.application.UnindexedContacts.Contact;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Gives bookings made before ADR-0034 their address index, so they appear in "my bookings". Runs at every start and does nothing
 * once every booking has an index (one look at an empty partial index). It walks the unindexed bookings in batches by id, so memory
 * is bounded by the batch and a stop part-way resumes where it left off; it changes only a booking that still has no index, so it is
 * safe beside live bookings, which are indexed when they are made. A booking whose address cannot be read is skipped and tried again
 * on the next start.
 */
@Component
public class ContactEmailIndexBackfill implements ApplicationRunner {

    static final int BATCH_SIZE = 500;
    private static final int SAMPLE_IDS = 5;

    private static final Logger log = LoggerFactory.getLogger(ContactEmailIndexBackfill.class);

    private final UnindexedContacts contacts;
    private final EmailIndex emailIndex;
    private final MyBookingsMetrics metrics;
    private final int batchSize;

    @Autowired
    public ContactEmailIndexBackfill(UnindexedContacts contacts, EmailIndex emailIndex, MyBookingsMetrics metrics) {
        this(contacts, emailIndex, metrics, BATCH_SIZE);
    }

    ContactEmailIndexBackfill(UnindexedContacts contacts, EmailIndex emailIndex, MyBookingsMetrics metrics, int batchSize) {
        this.contacts = contacts;
        this.emailIndex = emailIndex;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        int indexed = run();
        if (indexed > 0) {
            log.atInfo().addKeyValue("backfill.indexed", indexed).log("Indexed the contact address of older bookings");
        }
    }

    /** @return how many bookings were given an index. O(unindexed bookings), in batches. */
    public int run() {
        int indexed = 0;
        int unreadable = 0;
        List<Long> sample = new ArrayList<>();   // a few ids to start looking from, never all of them
        long after = 0;
        while (true) {
            List<Contact> batch = contacts.after(after, batchSize);
            for (Contact contact : batch) {
                if (contact.email() == null) {
                    unreadable++;
                    if (sample.size() < SAMPLE_IDS) {
                        sample.add(contact.id());
                    }
                } else if (contacts.setIndex(contact.id(), emailIndex.of(contact.email().value()))) {
                    indexed++;
                }
                after = contact.id();
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        metrics.backfilled(indexed);
        if (unreadable > 0) {
            // One line for the run, not one per booking: after a wrong or changed key this can be every old booking, at every start.
            metrics.unreadable(unreadable);
            log.atError().addKeyValue("backfill.unreadable", unreadable).addKeyValue("pnr.ids", sample)
                    .log("Some bookings' addresses could not be read, so they were not indexed (a changed SKYHUB_PII_KEY?); tried again at every start");
        }
        return indexed;
    }
}
