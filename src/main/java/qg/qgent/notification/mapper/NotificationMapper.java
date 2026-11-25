package qg.qgent.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import qg.qgent.notification.entity.NotificationEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知查询与已读更新。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<NotificationEntity> {

    /**
     * 查询指定用户的通知列表，按产生时间倒序，不分页。
     */
    default List<NotificationEntity> listByRecipient(Long recipientUserId) {
        return selectList(Wrappers.<NotificationEntity>lambdaQuery()
            .eq(NotificationEntity::getRecipientUserId, recipientUserId)
            .orderByDesc(NotificationEntity::getCreatedAt));
    }

    /**
     * 将属于指定用户的一条通知标记为已读；不属于该用户时返回 0（幂等）。
     */
    default int markRead(Long id, Long userId, LocalDateTime readTime) {
        return update(null, Wrappers.<NotificationEntity>lambdaUpdate()
            .set(NotificationEntity::getIsRead, true)
            .set(NotificationEntity::getReadAt, readTime)
            .eq(NotificationEntity::getId, id)
            .eq(NotificationEntity::getRecipientUserId, userId));
    }

    /**
     * 将指定用户的全部未读通知标记为已读（幂等）。
     */
    default int markAllRead(Long userId, LocalDateTime readTime) {
        return update(null, Wrappers.<NotificationEntity>lambdaUpdate()
            .set(NotificationEntity::getIsRead, true)
            .set(NotificationEntity::getReadAt, readTime)
            .eq(NotificationEntity::getRecipientUserId, userId)
            .eq(NotificationEntity::getIsRead, false));
    }
}
