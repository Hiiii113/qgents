package qg.qgent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.view.GroupLatestMessageRow;
import qg.qgent.chat.mapper.view.GroupUnreadRow;

import java.util.List;

/**
 * 群消息数据访问。
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    /**
     * 计算群内下一个单调递增消息序号。
     * <p>
     * 调用方必须在事务内先持有群行锁（chat_groups FOR UPDATE），避免并发重号。
     *
     * @param groupId 项目群聊 ID
     * @return 下一个序号（当前最大值 + 1，空群为 1）
     */
    Long nextSequence(@Param("groupId") Long groupId);

    /**
     * 按群内消息序号拉取增量消息，返回严格升序结果。
     * 调用方必须先完成项目群聊成员权限校验；sequence_no 是服务端分配的可靠游标。
     */
    List<MessageEntity> selectAfterSequence(
        @Param("groupId") Long groupId,
        @Param("afterSequence") long afterSequence,
        @Param("limit") int limit);

    /**
     * 批量查询指定群的最新一条消息摘要。调用方必须先完成项目与群成员权限校验。
     *
     * @param groupIds 已确认可见的群 ID
     * @return 每个有消息群的最新消息摘要
     */
    List<GroupLatestMessageRow> selectLatestByGroupIds(@Param("groupIds") List<Long> groupIds);

    /**
     * 按关键字检索项目内群消息的文本内容。
     * <p>
     * 仅检索当前项目下的消息（通过 chat_groups 归属限定，防跨项目泄露）；
     * 仅检索调用方已完成访问校验的可见群。关键字匹配 content JSON 的 {@code $.text}，按 sequence 倒序。
     *
     * @param projectId 项目 ID
     * @param groupIds  当前用户可见的群 ID，不能为空列表
     * @param q         关键字，不能为空
     * @param limit     返回条数上限
     * @return 匹配的消息实体列表，新的在前
     */
    List<MessageEntity> searchByQuery(
        @Param("projectId") Long projectId,
        @Param("groupIds") List<Long> groupIds,
        @Param("q") String q,
        @Param("limit") int limit);

    /**
     * 批量计算指定可见群的未读消息数（排除本人消息）。
     * <p>已读游标通过 LEFT JOIN 一次关联，避免对每条消息执行相关子查询。</p>
     *
     * @param groupIds 已确认可见的群 ID
     * @param userId   当前用户 ID
     * @return 群 ID → 未读数，仅返回未读数大于 0 的群
     */
    List<GroupUnreadRow> countUnreadByGroupIds(
        @Param("groupIds") List<Long> groupIds, @Param("userId") Long userId);

    /**
     * 统计当前用户在指定可见群中「@ 我」的未读消息数（排除本人消息）。
     * mentions 为 JSON 数组（如 {@code [{"type":"USER","id":"..."}]}），用 JSON_CONTAINS 匹配
     * {@code {"type":"USER","id":当前用户}}；未读 = sequence_no 大于已读游标。
     * <p>
     * 注意：mentions 里存的 id 是十进制字符串，而 {@code userId} 是数值参数；JSON_CONTAINS
     * 不会在字符串与数值之间隐式匹配，因此 JSON 匹配必须使用独立传入的字符串参数 {@code userIdText}。
     *
     * @param groupIds   已确认可见的群 ID
     * @param userId     当前用户 ID（用于游标/发送者比较）
     * @param userIdText 当前用户 ID 的字符串形式（JSON 匹配用）
     * @return 群 ID → @ 我的未读消息数
     */
    List<GroupUnreadRow> countMentionUnreadByGroupIds(
        @Param("groupIds") List<Long> groupIds,
        @Param("userId") Long userId,
        @Param("userIdText") String userIdText);

    /**
     * 解散团队时按群范围逐层删除不再被任何消息引用的叶子消息。
     * <p>
     * messages 存在自引用外键 {@code fk_msg_reply}（reply_to_message_id -&gt; id）且无级联，
     * 回复链可多级；单条批量 DELETE 无法在同一语句内删除互相引用的父子行，须循环调用
     * 本方法直至返回 0。回复引用限定在相同群集合内，保证逐层收敛。
     * <p>
     * MySQL 不允许在 DELETE 的 FROM/子查询中再次读取目标表（error 1093），因此把
     * 「被引用的父行 id」子查询用派生表 {@code AS tmp} 包一层，让 MySQL 先物化；
     * 同时用 {@code IS NOT NULL} 排除空值，避免 {@code NOT IN} 遇到 NULL 时整体失效。
     *
     * @param groupIds 待删除消息所属的项目群聊 ID 集合（非空）
     * @return 本次实际删除的行数；为 0 表示已无满足条件的消息
     */
    int deleteUnreferencedMessages(@Param("groupIds") List<Long> groupIds);
}
