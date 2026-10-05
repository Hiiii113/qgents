package qg.qgent.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@TableName("users")
public class UserEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private String email;
    private String displayName;
    private String avatarUrl;
    // 最近选择的团队；失去成员权限后由 /me 回退到首个可用团队。
    private UUID lastTeamId;
    // 当前头像对象键（OSS avatars/{userId}/...），用于替换旧头像时定位并删除；与 avatarUrl 成对维护。
    private String avatarObjectKey;
    private String passwordHash;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
