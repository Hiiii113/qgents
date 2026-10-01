package qg.qgent.sandbox.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class WorkerGitPushRequest {
    private String expectedHeadCommit;
    private String credentialGrantId;
}
