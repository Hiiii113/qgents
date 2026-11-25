package qg.qgent.mcp.service;

import org.springframework.http.HttpStatus;
import qg.qgent.common.web.ApiException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

final class McpEndpoint {
    private McpEndpoint() {}

    static URI parse(String endpoint) {
        try {
            URI uri = URI.create(endpoint.strip());
            if (isHttps(uri)) return uri;
        } catch (IllegalArgumentException ignored) {
            // The same validation error applies to malformed and unsupported URLs.
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MCP_INVALID_URL", "请输入有效的远程 HTTPS MCP 地址");
    }

    static boolean isPublicHttps(URI uri) {
        if (!isHttps(uri)) return false;
        try {
            requirePublicHost(uri);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    private static boolean isHttps(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
            && uri.getUserInfo() == null && uri.getFragment() == null && uri.getPort() <= 65535;
    }

    static void requirePublicHost(URI uri) {
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivate(address)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MCP_INVALID_URL", "MCP 地址必须指向公网服务");
                }
            }
        } catch (UnknownHostException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_UNREACHABLE", "无法解析 MCP 服务地址");
        }
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (bytes.length == 16) return (first & 0xe0) != 0x20;
        return first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
            || (first == 198 && (second == 18 || second == 19));
    }
}
