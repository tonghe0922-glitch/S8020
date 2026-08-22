package cn.shangjingu.platform.core.event;

public interface PlatformOutboxHandler {
    String eventType();

    String consumerName();

    void handle(PlatformOutboxEvent event);
}
