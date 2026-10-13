package qg.qgent.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 为公开业务接口补充中文分组和缺省说明，保留 Controller 的接口描述。 */
@Configuration
public class FrontendOpenApiConfiguration {

    @Bean
    OpenApiCustomizer frontendApiChineseDocumentation() {
        return this::documentPublicPaths;
    }

    private void documentPublicPaths(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().keySet().removeIf(path -> !path.startsWith("/api/v1/"));
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            operation.setTags(List.of(group(path)));
            if (operation.getSummary() == null || operation.getSummary().isBlank()) {
                operation.setSummary(summary(path, method));
            }
        }));
        openApi.setTags(List.of("认证与账户", "团队与邀请", "项目与项目成员", "GitHub App 与仓库导入",
                "群组与消息", "通知中心", "共享 Skill", "共享 Memory", "实时事件流")
                .stream().map(name -> new Tag().name(name)).toList());
    }

    private String group(String path) {
        if (path.contains("/integrations/github") || path.contains("/repositories"))
            return "GitHub App 与仓库导入";
        if (path.contains("/events")) return "实时事件流";
        if (path.startsWith("/api/v1/notifications")) return "通知中心";
        if (path.contains("/groups") || path.contains("/messages") || path.contains("/attachments"))
            return "群组与消息";
        if (path.contains("/skills")) return "共享 Skill";
        if (path.contains("/memories")) return "共享 Memory";
        if (path.contains("/auth/") || path.equals("/api/v1/me") || path.startsWith("/api/v1/me/")) return "认证与账户";
        if (path.contains("/projects")) return "项目与项目成员";
        return "团队与邀请";
    }

    private String summary(String path, PathItem.HttpMethod method) {
        if (path.endsWith("/integrations/github/installations"))
            return method == PathItem.HttpMethod.POST ? "生成 GitHub App 安装地址" : "查询团队 GitHub App 安装";
        if (path.endsWith("/integrations/github/repositories")) return "查询 App 已授权的可导入仓库";
        if (path.endsWith("/repositories"))
            return method == PathItem.HttpMethod.POST ? "导入仓库到项目" : "查询项目已导入仓库";
        if (path.contains("/repositories/") && method == PathItem.HttpMethod.DELETE) return "移除项目仓库导入记录";
        if (path.contains("/installations/") && path.endsWith("/sync")) return "同步 App 安装和仓库授权";
        if (path.contains("/installations/") && method == PathItem.HttpMethod.DELETE) return "解除团队 App 连接";
        if (path.equals("/api/v1/integrations/github/callback")) return "处理 App 安装回调";
        if (path.equals("/api/v1/integrations/github/webhook")) return "同步 App 安装与仓库授权变化";
        if (path.endsWith("/register")) return "注册账号";
        if (path.endsWith("/login")) return "登录";
        if (path.endsWith("/refresh")) return "刷新登录令牌";
        if (path.endsWith("/logout")) return "退出登录";
        if (path.endsWith("/password-reset-requests")) return "提交密码重置申请";
        if (path.endsWith("/password-resets")) return "重置密码";
        if (path.equals("/api/v1/me/current-team")) return "保存最近使用的团队";
        if (path.equals("/api/v1/me"))
            return method == PathItem.HttpMethod.GET ? "获取当前用户资料" : "修改当前用户资料";
        if (path.endsWith("/read-all")) return "全部通知已读";
        if (path.endsWith("/read")) return "标记已读";
        String resource = resource(path);
        if (method == PathItem.HttpMethod.GET) return "查询" + resource;
        if (method == PathItem.HttpMethod.PUT) {
            return "完整更新" + resource;
        }
        return switch (method) {
            case POST -> action(path, resource);
            case PATCH -> "修改" + resource;
            case DELETE -> "删除" + resource;
            default -> "操作" + resource;
        };
    }

    private String action(String path, String resource) {
        if (path.endsWith("/accept")) return "接受" + resource;
        if (path.endsWith("/reject")) return "拒绝" + resource;
        if (path.endsWith("/approve")) return "批准" + resource;
        if (path.endsWith("/cancel")) return "取消" + resource;
        if (path.endsWith("/archive")) return "归档" + resource;
        if (path.endsWith("/restore")) return "恢复" + resource;
        if (path.endsWith("/sync")) return "同步" + resource;
        if (path.endsWith("/leave")) return "退出" + resource;
        if (path.endsWith("/submit-review")) return "提交" + resource + "审核";
        return "创建" + resource;
    }

    private String resource(String path) {
        if (path.contains("attachments")) return "附件";
        if (path.contains("events")) return "项目事件流";
        if (path.contains("groups") && path.contains("members")) return "群成员";
        if (path.contains("invitations")) return "团队邀请";
        if (path.contains("members") && path.contains("/teams/")) return "团队成员";
        if (path.contains("members") && path.contains("/projects/")) return "项目成员";
        if (path.contains("members")) return "成员";
        if (path.contains("groups")) return "群组";
        if (path.contains("memories")) return "Memory";
        if (path.contains("messages")) return "群消息";
        if (path.contains("skills")) return "Skill";
        if (path.contains("notifications")) return "通知";
        if (path.contains("teams")) return "团队";
        if (path.contains("projects")) return "项目";
        return "资源";
    }

}
