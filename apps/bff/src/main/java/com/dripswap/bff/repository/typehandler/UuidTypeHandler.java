package com.dripswap.bff.repository.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * Postgres UUID <-> java.util.UUID.
 *
 * MyBatis doesn't always auto-register a UUID handler depending on the bundled mybatis version,
 * so we register our own to make mapper parsing deterministic.
 */
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object v = rs.getObject(columnName);
        return toUuid(v);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Object v = rs.getObject(columnIndex);
        return toUuid(v);
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Object v = cs.getObject(columnIndex);
        return toUuid(v);
    }

    private static UUID toUuid(Object v) {
        if (v == null) return null;
        if (v instanceof UUID u) return u;
        return UUID.fromString(String.valueOf(v));
    }
}

