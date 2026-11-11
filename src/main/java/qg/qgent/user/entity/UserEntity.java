package qg.qgent.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("users")
public class UserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;
    private String displayName;
    private String avatarUrl;
    // 最近选择的团队；失去成员权限后由 /me 回退到首个可用团队。
    private Long lastTeamId;
    private String passwordHash;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
