package cn.shangjingu.platform.worker;

import org.springframework.scheduling.annotation.Scheduled;

public final class Phase09P002ExpiryPump {
    private final Phase09P002ExpiryWorker worker;
    private final int batchSize;

    public Phase09P002ExpiryPump(Phase09P002ExpiryWorker worker, int batchSize) {
        if (worker == null || batchSize <= 0) throw new IllegalArgumentException("P002 expiry pump configuration is invalid");
        this.worker = worker;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${platform.phase09.p002.expiry.poll-delay-ms:1000}")
    public void pump() {
        worker.runOnce(batchSize);
    }
}
