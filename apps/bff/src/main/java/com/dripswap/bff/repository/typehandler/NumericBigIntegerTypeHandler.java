package com.dripswap.bff.repository.typehandler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * Postgres NUMERIC(78,0) <-> BigInteger.
 */
public class NumericBigIntegerTypeHandler extends BaseTypeHandler<BigInteger> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, BigInteger parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setBigDecimal(i, new BigDecimal(parameter));
    }

    @Override
    public BigInteger getNullableResult(ResultSet rs, String columnName) throws SQLException {
        BigDecimal v = rs.getBigDecimal(columnName);
        return v == null ? null : v.toBigInteger();
    }

    @Override
    public BigInteger getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        BigDecimal v = rs.getBigDecimal(columnIndex);
        return v == null ? null : v.toBigInteger();
    }

    @Override
    public BigInteger getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        BigDecimal v = cs.getBigDecimal(columnIndex);
        return v == null ? null : v.toBigInteger();
    }
}

