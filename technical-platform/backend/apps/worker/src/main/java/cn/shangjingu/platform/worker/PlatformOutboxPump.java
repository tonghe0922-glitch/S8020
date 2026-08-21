package cn.shangjingu.platform.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class PlatformOutboxPump {
    private static final Logger log = LoggerFactory.getLogger(PlatformOutboxPump.class);

    private final PlatformOutboxWorker worker;
    private final int batchSize;

    PlatformOutboxPump(PlatformOutboxWorker worker, int batchSize) {
        if (worker == null) throw new IllegalArgumentException("worker is required");
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        this.worker = worker;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${platform.outbox.poll-delay-ms:1000}")
    public void poll() {
        try {
            worker.runOnce(batchSize);
        } catch (RuntimeException failure) {
            log.error("platform outbox poll failed; next scheduled poll will retry", failure);
        }
    }
}
