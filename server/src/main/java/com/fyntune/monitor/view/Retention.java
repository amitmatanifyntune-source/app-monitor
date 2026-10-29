package com.fyntune.monitor.view;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes old monitoring data every hour, by the periods in app.retention.*. */
@Component
public class Retention {
    private static final Logger log = LoggerFactory.getLogger(Retention.class);
    private final JdbcTemplate jdbc;
    private final int eventsDays, minutesDays, samplesDays;

    public Retention(JdbcTemplate jdbc, @Value("${app.retention.events-days}") int eventsDays,
                     @Value("${app.retention.minutes-days}") int minutesDays, @Value("${app.retention.samples-days}") int samplesDays) {
        this.jdbc = jdbc;
        this.eventsDays = eventsDays;
        this.minutesDays = minutesDays;
        this.samplesDays = samplesDays;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void purge() {
        try {
            long minute = System.currentTimeMillis() / 60000 - minutesDays * 1440L;
            int n = jdbc.update("DELETE FROM event WHERE at < now() - make_interval(days => ?)", eventsDays)
                  + jdbc.update("DELETE FROM check_result WHERE at < now() - make_interval(days => ?)", eventsDays)
                  + jdbc.update("DELETE FROM alert_log WHERE at < now() - make_interval(days => ?)", eventsDays)
                  + jdbc.update("DELETE FROM minute_stat WHERE minute < ?", minute)
                  + jdbc.update("DELETE FROM endpoint_minute WHERE minute < ?", minute)
                  + jdbc.update("DELETE FROM db_sample WHERE at < now() - make_interval(days => ?)", samplesDays)
                  + jdbc.update("DELETE FROM host_sample WHERE at < now() - make_interval(days => ?)", samplesDays);
            if (n > 0) log.info("Retention: removed {} old rows", n);
        } catch (Exception e) {
            log.warn("Retention run failed: {}", e.getMessage());
        }
    }
}
