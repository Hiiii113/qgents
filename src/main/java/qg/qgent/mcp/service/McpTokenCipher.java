package qg.qgent.mcp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.common.web.ApiException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
class McpTokenCipher {
    private static final int NONCE_SIZE = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    McpTokenCipher(@Value("${app.mcp.encryption-key}") String configuredKey) {
        byte[] bytes = configuredKey.isBlank() ? null : Base64.getDecoder().decode(configuredKey);
        if (bytes != null && bytes.length != 32) throw new IllegalArgumentException("MCP_TOKEN_ENCRYPTION_KEY 需要 32 字节的 Base64 密钥");
        key = bytes == null ? null : new SecretKeySpec(bytes, "AES");
    }

    void requireConfigured() {
        if (key == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MCP_NOT_CONFIGURED", "服务端尚未配置 MCP 凭证加密密钥");
        }
    }

    String encrypt(String token, long credentialId) {
        if (token == null) return null;
        byte[] nonce = new byte[NONCE_SIZE];
        random.nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, nonce, token.getBytes(StandardCharsets.UTF_8), credentialId);
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
            .put(nonce).put(encrypted).array());
    }

    String decrypt(String token, long credentialId) {
        if (token == null) return null;
        var packed = ByteBuffer.wrap(Base64.getDecoder().decode(token));
        byte[] nonce = new byte[NONCE_SIZE];
        packed.get(nonce);
        byte[] encrypted = new byte[packed.remaining()];
        packed.get(encrypted);
        return new String(crypt(Cipher.DECRYPT_MODE, nonce, encrypted, credentialId), StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] value, long credentialId) {
        requireConfigured();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(credentialId).array());
            return cipher.doFinal(value);
        } catch (GeneralSecurityException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MCP_CREDENTIAL_CRYPTO_ERROR", "MCP 凭证无法读取或保存");
        }
    }
}
