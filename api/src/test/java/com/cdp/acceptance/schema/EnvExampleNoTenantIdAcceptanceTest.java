package com.cdp.acceptance.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * tenant-id-drop（D-063）：.env.example 不得再含 CDP_TENANT_ID。
 * 從 user.dir 往上找 .env.example，Maven 在 api/ 或 repo 根目錄執行都可。
 */
class EnvExampleNoTenantIdAcceptanceTest {

    private static Path findEnvExample() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(".env.example");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError(".env.example not found above " + System.getProperty("user.dir"));
    }

    @Test
    void envExampleDoesNotContainTenantId() throws Exception {
        String content = Files.readString(findEnvExample());
        assertThat(content).doesNotContain("CDP_TENANT_ID");
    }
}
