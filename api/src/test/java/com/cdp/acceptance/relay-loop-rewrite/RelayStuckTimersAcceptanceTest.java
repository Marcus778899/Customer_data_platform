package com.cdp.acceptance.relayloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * relay 卡住（broker 無回應，等 confirm 逾時）時，計時器迴圈仍照常推進。
 * 排程迴圈全部開啟（真實排程），排程執行緒池 = 4；confirm 逾時拉長到 PT8S，確保 relay 的阻塞期涵蓋觀察窗。
 * 舊行為（單一排程執行緒）下，relay 阻塞期間 AdvanceLoop 不會被排到。
 */
@SpringBootTest(
        properties = {
            "cdp.engine.poll-interval=PT0.2S",
            "cdp.relay.poll-interval=PT0.2S",
            "cdp.relay.confirm-timeout=PT8S",
            "cdp.relay.lease=PT30S",
            "spring.task.scheduling.pool.size=4"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RelayLoopTestSupport.class)
class RelayStuckTimersAcceptanceTest extends RelayLoopBase {

    @Autowired RabbitMQContainer rabbitContainer;

    private static final Duration WAIT = Duration.ofSeconds(30);

    private String enrollOne(String eventName) throws Exception {
        String profile = postEvent(eventName);
        await().atMost(WAIT).until(() -> enrollmentOf(profile) != null);
        return enrollmentOf(profile);
    }

    @Test
    void relayBlockedOnConfirm_timersStillAdvance() throws Exception {
        // 三個旅程：等 1／2／3 小時；同一時間點全部建好 Enrollment
        String warmEvent = createJourney("PT1H");
        String slowEvent = createJourney("PT2H");
        String probeEvent = createJourney("PT3H");
        String warm = enrollOne(warmEvent);
        String slow = enrollOne(slowEvent);
        String probe = enrollOne(probeEvent);

        // 暖機：先走通一次完整發送，讓連線與 channel 建立
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        await().atMost(WAIT).until(() -> outboxKeyOf(warm) != null);
        String warmKey = outboxKeyOf(warm);
        await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(warmKey)));

        var docker = DockerClientFactory.instance().client();
        String containerId = rabbitContainer.getContainerId();
        docker.pauseContainerCmd(containerId).exec();
        try {
            // 第二筆到期 → 寫入 outbox → relay 搶到後卡在等 confirm
            clock.advance(Duration.ofHours(1));
            await().atMost(Duration.ofSeconds(5)).until(() -> outboxKeyOf(slow) != null);
            String slowKey = outboxKeyOf(slow);
            // 給 relay 時間進入等待
            Thread.sleep(1000);
            assertThat(outboxStatus(slowKey)).as("relay 還卡在等 confirm，列未回寫為 PUBLISHED").isEqualTo("PENDING");

            // 第三筆到期：relay 仍卡住（逾時 8 秒），計時器必須在 4 秒內照常推進
            long t0 = System.nanoTime();
            clock.advance(Duration.ofHours(1));
            await().atMost(Duration.ofSeconds(4)).until(() -> outboxKeyOf(probe) != null);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertThat(elapsedMs).as("relay 阻塞期間計時器仍在數秒內推進").isLessThan(4_000);
            assertThat(outboxStatus(slowKey))
                    .as("觀察時 relay 仍未完成（確認測試窗口確實落在阻塞期內）")
                    .isEqualTo("PENDING");
        } finally {
            docker.unpauseContainerCmd(containerId).exec();
        }
    }
}
