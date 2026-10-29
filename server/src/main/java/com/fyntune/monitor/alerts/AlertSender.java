package com.fyntune.monitor.alerts;

/** Delivers one alert to one channel; throws with a message the administrator can act on when delivery fails. */
public interface AlertSender {
    void send(String kind, String target, Alert alert) throws Exception;

    /** False when the server has no WhatsApp credentials, so WhatsApp channels cannot be added or used. */
    boolean whatsappReady();
}
