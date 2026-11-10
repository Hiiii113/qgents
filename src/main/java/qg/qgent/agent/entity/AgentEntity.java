package qg.qgent.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目内 Agent；每个项目创建时得到自己的默认 Agent 记录，ID 自增且互不复用。 */
@Data
@TableName("agents")
public class AgentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String name;
    private Boolean defaultAgent;
    private LocalDateTime createdAt;
}
