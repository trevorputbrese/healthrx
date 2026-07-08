package com.healthrx.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Null-safe ResultSet column readers shared by the repository row mappers. */
public final class Columns {

    private Columns() {
    }

    public static UUID uuid(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : (UUID) v;
    }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    public static LocalDate localDate(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    public static BigDecimal money(ResultSet rs, String col) throws SQLException {
        return rs.getBigDecimal(col);
    }

    public static Integer integerOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    /** Binds an {@link Instant} as an {@code OffsetDateTime} for a timestamptz parameter. */
    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
