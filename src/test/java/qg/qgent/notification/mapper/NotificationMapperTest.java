package qg.qgent.notification.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.support.MapperTestSupport;
import qg.qgent.support.TestIds;

/**
 * 通知的简单条件更新走 MyBatis-Plus lambda wrapper，列名与属性名由框架解析。
 */
class NotificationMapperTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(false);
        when(statement.getUpdateCount()).thenReturn(1);
        var configuration = MapperTestSupport.configuration(NotificationMapper.class);
        configuration.setEnvironment(
            new Environment("test", new JdbcTransactionFactory(), dataSource));
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void marksOnlyTheOwnersNotificationRead() throws Exception {
        Long id = TestIds.next();
        Long userId = TestIds.next();

        try (SqlSession session = factory.openSession()) {
            assertThat(session.getMapper(NotificationMapper.class)
                .markRead(id, userId, LocalDateTime.now())).isEqualTo(1);
        }

        assertThat(updateSql())
            .contains("UPDATE notifications")
            .contains("SET is_read=?,read_at=?")
            .contains("WHERE", "recipient_user_id", "id");
    }

    @Test
    void marksAllUnreadNotificationsOfTheUserRead() throws Exception {
        Long userId = TestIds.next();

        try (SqlSession session = factory.openSession()) {
            assertThat(session.getMapper(NotificationMapper.class)
                .markAllRead(userId, LocalDateTime.now())).isEqualTo(1);
        }

        assertThat(updateSql())
            .contains("UPDATE notifications")
            .contains("SET is_read=?,read_at=?")
            .contains("WHERE", "recipient_user_id", "is_read");
    }

    private String updateSql() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        return sql.getValue();
    }
}
