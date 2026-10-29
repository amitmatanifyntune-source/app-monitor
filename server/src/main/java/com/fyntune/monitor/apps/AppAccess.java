package com.fyntune.monitor.apps;

import com.fyntune.monitor.auth.AuthUser;
import com.fyntune.monitor.auth.CurrentUser;
import com.fyntune.monitor.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** Which apps the signed-in user may see. An app outside the user's access reads as not found. */
@Service
public class AppAccess {
    private final JdbcTemplate jdbc;

    public AppAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The app's id, if the current user may see it. */
    public long require(String code) {
        AuthUser u = CurrentUser.get();
        List<Long> ids = u.admin()
                ? jdbc.queryForList("SELECT id FROM app WHERE code = ?", Long.class, code)
                : jdbc.queryForList("SELECT a.id FROM app a JOIN app_access x ON x.app_id = a.id WHERE a.code = ? AND x.user_id = ?", Long.class, code, u.id());
        if (ids.isEmpty()) throw ApiException.notFound("App " + code);
        return ids.get(0);
    }

    /** Ids of every app the current user may see. */
    public List<Long> visible() {
        AuthUser u = CurrentUser.get();
        return u.admin()
                ? jdbc.queryForList("SELECT id FROM app ORDER BY name", Long.class)
                : jdbc.queryForList("SELECT a.id FROM app a JOIN app_access x ON x.app_id = a.id WHERE x.user_id = ? ORDER BY a.name", Long.class, u.id());
    }
}
