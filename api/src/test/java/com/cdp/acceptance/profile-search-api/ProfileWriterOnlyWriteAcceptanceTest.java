package com.cdp.acceptance.profilesearchapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * profile-search-api 驗收：DESIGN-03 做法 #4——客戶的所有寫入（PUT API、事件攝取建空白客戶）只經
 * {@code com.cdp.profile.ProfileWriter}（合併、正規化、衝突判定的唯一實作）。這是結構性約束，沒有對外行為可測，所以用 ArchUnit
 * 與原始碼掃描；只斷言「誰能寫 profile／profile_identifier 表」與「入口都用 ProfileWriter」，不測內部細節。
 */
class ProfileWriterOnlyWriteAcceptanceTest {

    private static final String WRITER = "com.cdp.profile.ProfileWriter";

    private static com.tngtech.archunit.core.domain.JavaClasses classes() {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.cdp");
    }

    @Test
    void profileWriterExists() {
        assertThat(classes().contain(WRITER)).as("%s 必須存在", WRITER).isTrue();
    }

    @Test
    void eventIngestionService_createsBlankProfilesThroughProfileWriter() {
        JavaClass ingestion = classes().get("com.cdp.ingest.EventIngestionService");
        assertThat(ingestion.getDirectDependenciesFromSelf().stream().map(d -> d.getTargetClass().getName()))
                .contains(WRITER);
    }

    @Test
    void profileApiService_writesThroughProfileWriter() {
        JavaClass service = classes().get("com.cdp.profile.ProfileService");
        assertThat(service.getDirectDependenciesFromSelf().stream().map(d -> d.getTargetClass().getName()))
                .contains(WRITER);
    }

    @Test
    void onlyProfileWriterSourceInsertsOrUpdatesProfileTables() throws IOException {
        Path root = findMainSources();
        Pattern write =
                Pattern.compile(
                        "(insert\\s+into\\s+profile(_identifier)?\\b|update\\s+profile(_identifier)?\\s+set\\b|delete\\s+from\\s+profile(_identifier)?\\b)",
                        Pattern.CASE_INSENSITIVE);
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders =
                    files.filter(p -> p.toString().endsWith(".java"))
                            .filter(
                                    p -> {
                                        try {
                                            return write.matcher(Files.readString(p)).find();
                                        } catch (IOException e) {
                                            throw new IllegalStateException(e);
                                        }
                                    })
                            .map(p -> p.getFileName().toString())
                            .toList();
        }
        assertThat(offenders).as("寫 profile／profile_identifier 的 SQL 只能出現在 ProfileWriter").containsExactly("ProfileWriter.java");
    }

    private static Path findMainSources() {
        Path p = Paths.get("src/main/java").toAbsolutePath();
        if (!Files.isDirectory(p)) p = Paths.get("api/src/main/java").toAbsolutePath();
        assertThat(p).as("找不到 src/main/java").isDirectory();
        return p;
    }
}
