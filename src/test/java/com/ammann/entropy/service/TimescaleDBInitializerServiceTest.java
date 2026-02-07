package com.ammann.entropy.service;

import com.ammann.entropy.exception.SomeThingWentWrongException;
import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimescaleDBInitializerServiceTest
{

    @Test
    void createsHypertableWhenMissing()
    {
        AgroalDataSource ds = stubDataSource(false);
        TimescaleDBInitializerService service = new TimescaleDBInitializerService(ds);

        assertThatCode(service::initializeTimescaleDB).doesNotThrowAnyException();
    }

    @Test
    void doesNothingWhenHypertableExists()
    {
        AgroalDataSource ds = stubDataSource(true);
        TimescaleDBInitializerService service = new TimescaleDBInitializerService(ds);

        assertThatCode(service::initializeTimescaleDB).doesNotThrowAnyException();
    }

    @Test
    void wrapsSqlErrors()
    {
        AgroalDataSource failingDs = failingDataSource();
        TimescaleDBInitializerService service = new TimescaleDBInitializerService(failingDs);

        assertThatThrownBy(service::initializeTimescaleDB)
                .isInstanceOf(SomeThingWentWrongException.class);
    }

    private AgroalDataSource stubDataSource(boolean hypertableExists)
    {
        Connection connection = connectionStub(hypertableExists);
        return (AgroalDataSource) Proxy.newProxyInstance(
                AgroalDataSource.class.getClassLoader(),
                new Class[]{AgroalDataSource.class},
                (proxy, method, args) -> {
                    if (Set.of("getConnection", "getConnectionPool").contains(method.getName())) {
                        return connection;
                    }
                    // Minimal contract for tests
                    if (method.getReturnType().equals(void.class)) return null;
                    return defaultValue(method.getReturnType());
                });
    }

    private AgroalDataSource failingDataSource()
    {
        return (AgroalDataSource) Proxy.newProxyInstance(
                AgroalDataSource.class.getClassLoader(),
                new Class[]{AgroalDataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("getConnection")) {
                        throw new SQLException("boom");
                    }
                    if (method.getReturnType().equals(void.class)) return null;
                    return defaultValue(method.getReturnType());
                });
    }

    private Connection connectionStub(boolean hypertableExists)
    {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        String sql = (String) args[0];
                        if (sql.contains("timescaledb_information.hypertables")) {
                            return preparedStatementStub(true, hypertableExists);
                        }
                        return preparedStatementStub(false, false);
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if (method.getReturnType().equals(void.class)) return null;
                    return defaultValue(method.getReturnType());
                });
    }

    private PreparedStatement preparedStatementStub(boolean returnsResultSet, boolean hypertableExists)
    {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "executeQuery" -> {
                            return resultSetStub(hypertableExists);
                        }
                        case "execute" -> {
                            return true;
                        }
                        case "close" -> {
                            return null;
                        }
                        default -> {
                            if (method.getReturnType().equals(void.class)) return null;
                            return defaultValue(method.getReturnType());
                        }
                    }
                });
    }

    private ResultSet resultSetStub(boolean hasRow)
    {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        return hasRow;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if (method.getReturnType().equals(void.class)) return null;
                    return defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> type)
    {
        if (type.equals(boolean.class)) return false;
        if (type.equals(int.class) || type.equals(short.class) || type.equals(byte.class)) return 0;
        if (type.equals(long.class)) return 0L;
        if (type.equals(float.class)) return 0f;
        if (type.equals(double.class)) return 0d;
        return null;
    }
}
