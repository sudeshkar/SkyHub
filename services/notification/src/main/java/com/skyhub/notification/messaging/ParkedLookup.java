package com.skyhub.notification.messaging;

/** Asks the dead-letter topic whether a message is still waiting there, so an operator cannot skip something that is not. */
@FunctionalInterface
interface ParkedLookup {

    /** True if the partition exists and the offset is at or after what the replay has taken and before the end of the partition. */
    boolean isWaiting(int partition, long offset);
}
