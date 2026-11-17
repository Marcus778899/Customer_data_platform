package com.cdp.acceptance.maskingdefaultapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 經 make 啟動時的環境：.env 沒有 CDP_TRACE_PLAIN_KEYS，子程序環境不可出現該變數（不是空字串），
 * 否則 Spring 的 ${CDP_TRACE_PLAIN_KEYS:name} 不套預設、姓名被全遮。不啟 Spring、不用 Docker。
 */
class MakefileExportTest {

    private static final String VAR = "CDP_TRACE_PLAIN_KEYS";
    private static Path repoRoot;

    @BeforeAll
    static void locate() throws Exception {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("Makefile"))) {
            p = p.getParent();
        }
        assumeTrue(p != null, "Makefile not found");
        repoRoot = p;
        Process v = new ProcessBuilder("make", "--version").redirectErrorStream(true).start();
        v.getInputStream().readAllBytes();
        assumeTrue(v.waitFor(30, TimeUnit.SECONDS) && v.exitValue() == 0, "make not available");
    }

    /** 用 ROOT 覆寫指到暫時目錄（其 .env 內容由呼叫端決定），再用附加的 makefile 印出子程序環境。 */
    private List<String> childEnv(Path tmp, String dotenv) throws IOException, InterruptedException {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.writeString(root.resolve(".env"), dotenv, StandardCharsets.UTF_8);
        Path probe = tmp.resolve("probe.mk");
        Files.writeString(probe, "cdp-probe:\n\t@env\n", StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(
                "make", "-f", "Makefile", "-f", probe.toString().replace((char) 92, (char) 47),
                "ROOT=" + root.toString().replace((char) 92, (char) 47), "cdp-probe");
        pb.directory(repoRoot.toFile());
        pb.environment().remove(VAR);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(proc.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(proc.exitValue()).as(out).isZero();
        return out.lines().toList();
    }

    @Test
    void undefinedVariableIsNotExportedAsEmptyString(@TempDir Path tmp) throws Exception {
        List<String> env = childEnv(tmp, "SOME_OTHER_VAR=1\n");
        assertThat(env).noneMatch(l -> l.startsWith(VAR + "="));
    }

    @Test
    void definedVariableIsStillExported(@TempDir Path tmp) throws Exception {
        List<String> env = childEnv(tmp, VAR + "=name,phone\n");
        assertThat(env).contains(VAR + "=name,phone");
    }
}
