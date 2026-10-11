package ding.co.backendportfolio.chapter4._5_event_with_external_update;

import ding.co.backendportfolio.chapter2.entity.Member;
import ding.co.backendportfolio.chapter4._3_event_with_lock.entity.EventWithLock;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.ExternalEventApi;
import ding.co.backendportfolio.chapter4._4_event_with_external.external.model.ExternalEventResponse;
import ding.co.backendportfolio.chapter4._4_event_with_external.service.EventExternalUpdateService;
import ding.co.backendportfolio.chapter4._5_event_with_external_update.facade.EventJoinWithExternalApiUpdateFacade;
import ding.co.backendportfolio.chapter4.fixture.Chapter4Fixture;
import ding.co.backendportfolio.chapter4.fixture.ConcurrentTestUtil;
import ding.co.backendportfolio.config.IntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@Slf4j
@IntegrationTest
class EventJoinWithExternalApiUpdateFacadeTest {
    @Autowired
    private EventJoinWithExternalApiUpdateFacade externalApiResponseFacade;
    @MockBean
    private EventExternalUpdateService eventJoinService;
    @SpyBean
    private ExternalEventApi externalEventApi;

    private EventWithLock testEvent;
    private List<Member> testMembers;
    private AtomicInteger externalApiCallCount;
    private AtomicInteger updateExternalIdCallCount;
    private AtomicInteger updateExternalIdSuccessCount;

    @BeforeEach
    void setUp() {
        testEvent = Chapter4Fixture.createEventWithLock("테스트 이벤트", 20);
        testMembers = IntStream.range(0, 20)
                .mapToObj(i -> Chapter4Fixture.createTestMember("테스트유저" + i))
                .collect(Collectors.toList());

        externalApiCallCount = new AtomicInteger(0);
        updateExternalIdCallCount = new AtomicInteger(0);
        updateExternalIdSuccessCount = new AtomicInteger(0);

        doAnswer(invocation -> {
            Long eventId = invocation.getArgument(0);
            Long memberId = invocation.getArgument(1);
            Member testMember = Chapter4Fixture.createTestMember("테스트유저" + memberId);
            return Chapter4Fixture.createTestParticipant(testEvent, testMember);
        }).when(eventJoinService).joinEventWithTransaction(any(), any());

        doAnswer(invocation -> {
            int attempt = updateExternalIdCallCount.incrementAndGet();

            if (attempt % 2 == 0) {
                updateExternalIdSuccessCount.incrementAndGet();
                return null;
            }

            throw new RuntimeException("DB 업데이트 실패 시뮬레이션");
        }).when(eventJoinService).updateExternalId(any(), any());

        doAnswer(invocation -> {
            externalApiCallCount.incrementAndGet();
            return ExternalEventResponse.builder()
                    .success(true)
                    .externalId(UUID.randomUUID().toString())
                    .errorMessage(null)
                    .build();
        }).when(externalEventApi).registerParticipant(any(), any(), any());
    }

    @Test
    @DisplayName("외부 API 호출과 DB 업데이트 불일치 테스트")
    void externalApiCallAndDbUpdateMismatchTest() throws InterruptedException {
        // when
        ConcurrentTestUtil.executeConcurrentJoins(
            testEvent.getId(),
            testMembers,
            (eventId, memberId) -> externalApiResponseFacade.joinEvent(eventId, memberId)
        );

        // then
        logTestResults();

        assertThat(externalApiCallCount.get()).isEqualTo(20);
        assertThat(updateExternalIdCallCount.get()).isEqualTo(20);
        assertThat(updateExternalIdSuccessCount.get()).isEqualTo(10);

        assertThat(externalApiCallCount.get() - updateExternalIdSuccessCount.get())
                .as("외부 API와 DB 업데이트 불일치 건수")
                .isEqualTo(10);
    }

    private void logTestResults() {
        log.info("=== 외부 API 호출과 DB 업데이트 불일치 테스트 결과 ===");
        log.info("총 요청 수: {}", 20);
        log.info("외부 API 호출 횟수: {}", externalApiCallCount.get());
        log.info("DB 업데이트 시도 횟수: {}", updateExternalIdCallCount.get());
        log.info("DB 업데이트 성공 횟수: {}", updateExternalIdSuccessCount.get());
        log.info("불일치 건수: {}", (externalApiCallCount.get() - updateExternalIdSuccessCount.get()));
        log.info("DB 업데이트 성공률: {}%",
                String.format("%.2f",
                        (double) updateExternalIdSuccessCount.get()
                                / updateExternalIdCallCount.get() * 100));
    }
} 