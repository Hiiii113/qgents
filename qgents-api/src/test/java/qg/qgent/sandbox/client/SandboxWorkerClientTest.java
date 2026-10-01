package qg.qgent.sandbox.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import qg.qgent.common.web.ApiException;
import qg.qgent.sandbox.dto.WorkerWorkspaceProvisionRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SandboxWorkerClientTest {
    @Test
    void provisioningOnlySendsProjectIdAndReturnsStorageKey() {
        UUID id = UUID.randomUUID(), project = UUID.randomUUID();
        var builder = RestClient.builder().baseUrl("http://worker");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://worker/internal/v1/workspaces/" + id)).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"projectId\":\"" + project + "\"}"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"projectId\":\"" + project
                        + "\",\"storageKey\":\"workspaces/" + id + "\",\"status\":\"READY\"}", MediaType.APPLICATION_JSON));
        var request = new WorkerWorkspaceProvisionRequest();
        request.setProjectId(project);
        var result = new SandboxWorkerClient(builder.build(), new ObjectMapper()).provisionWorkspace(id, request);
        assertThat(result.getStorageKey()).isEqualTo("workspaces/" + id);
        server.verify();
    }

    @Test
    void workerStructuredFailureRemainsVisibleToCaller() {
        var builder = RestClient.builder().baseUrl("http://worker");
        var server = MockRestServiceServer.bindTo(builder).build();
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://worker/internal/v1/workspaces/" + id))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"WORKSPACE_SPEC_CONFLICT\",\"message\":\"工作区属于其他项目\"}"));
        var client = new SandboxWorkerClient(builder.build(), new ObjectMapper());
        assertThatThrownBy(() -> client.getWorkspace(id)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("WORKSPACE_SPEC_CONFLICT"));
        server.verify();
    }
}
