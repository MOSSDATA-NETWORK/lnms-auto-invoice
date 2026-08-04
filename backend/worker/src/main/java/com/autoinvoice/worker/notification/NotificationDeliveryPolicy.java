package com.autoinvoice.worker.notification;

final class NotificationDeliveryPolicy {
    private NotificationDeliveryPolicy() {
    }

    static EmailAction emailAction(String status) {
        return switch (status) {
            case "SENT" -> EmailAction.RETURN_SENT;
            case "SENDING" -> EmailAction.MARK_UNCERTAIN;
            case "UNCERTAIN" -> EmailAction.RETURN_UNCERTAIN;
            case "PENDING", "RETRY", "FAILED", "DEAD" -> EmailAction.SEND;
            default -> EmailAction.REJECT;
        };
    }

    enum EmailAction {
        SEND,
        RETURN_SENT,
        MARK_UNCERTAIN,
        RETURN_UNCERTAIN,
        REJECT
    }
}
