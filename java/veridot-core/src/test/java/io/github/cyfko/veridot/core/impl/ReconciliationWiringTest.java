package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationWiringTest {

    private InMemoryBroker broker;
    private GenericSignerVerifier sv;
    private ScheduledThreadPoolExecutor customScheduler;

    @BeforeEach
    void setUp() throws Exception {
        broker = new InMemoryBroker();
        sv = TestTrustSetup.create().newSignerVerifier(broker);

        // Replace the scheduler with a custom one that runs tasks with a 10ms delay instead of 15 minutes
        customScheduler = new ScheduledThreadPoolExecutor(2) {
            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
                // Force a very short 10ms delay for unit tests
                return super.scheduleWithFixedDelay(command, 10, 10, TimeUnit.MILLISECONDS);
            }
        };

        Field schedulerField = GenericSignerVerifier.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        // Shutdown the old scheduler
        ScheduledThreadPoolExecutor oldScheduler = (ScheduledThreadPoolExecutor) schedulerField.get(sv);
        oldScheduler.shutdownNow();

        // Inject custom scheduler
        schedulerField.set(sv, customScheduler);
    }

    @AfterEach
    void tearDown() {
        if (sv != null) {
            sv.close();
        }
        if (customScheduler != null) {
            customScheduler.shutdownNow();
        }
    }

    @Test
    void reconciliation_is_started_on_first_sign() throws Exception {
        sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());

        // Wait up to 2 seconds for the reconciliation task to run and write the SNAPSHOT_MARKER
        Scope scope = Scope.group("u1");
        boolean markerFound = false;
        for (int i = 0; i < 40; i++) {
            boolean hasMarker = broker.snapshot(scope).stream()
                    .anyMatch(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.SNAPSHOT_MARKER);
            if (hasMarker) {
                markerFound = true;
                break;
            }
            Thread.sleep(50);
        }

        assertTrue(markerFound, "SNAPSHOT_MARKER must appear in the broker snapshot after reconciliation runs");
    }

    @Test
    void reconciliation_is_idempotent_across_multiple_signs() {
        sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").validity(600).build());

        // There should be exactly 1 reconciliation task scheduled for the scope group "u1"
        assertEquals(1, sv.reconciliationManager.tasksCountForTest(),
                "Only one reconciliation task should be scheduled for the same scope");
    }

    @Test
    void close_stops_all_reconciliation_tasks() throws Exception {
        sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());

        assertEquals(1, sv.reconciliationManager.tasksCountForTest());

        sv.close();

        assertEquals(0, sv.reconciliationManager.tasksCountForTest(),
                "All reconciliation tasks must be cancelled and cleared upon close()");
    }
}
