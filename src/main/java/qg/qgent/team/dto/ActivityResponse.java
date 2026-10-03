package qg.qgent.team.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 团队最近动态条目（聚合自项目事件，覆盖最近 24 小时保留窗口）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityResponse {
    private String id;
    private String type;
    private String title;
    private String summary;
    private ActivityActor actor;
    private ActivityTarget target;
    private String link;
    private String createdAt;

    /** 动态发起者（用户）；avatar 当前无来源，恒为 null。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityActor {
        private String id;
        private String displayName;
        private String avatar;
    }

    /** 动态关联的项目。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityTarget {
        private String type;
        private String id;
        private String title;
    }
}
