package com.npick;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.npick.clip.application.port.RegistrationDeduplicationPort;
import com.npick.clip.domain.repository.ClipRegistrationRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "NPICK_MIGRATION_TEST_URL", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
class NpickApplicationTests {
    @MockitoBean
    private ClipRegistrationRepository clipRegistrationRepository;

    // This context deliberately excludes DataSource auto-configuration; DB wiring is verified separately.
    @MockitoBean
    private RegistrationDeduplicationPort registrationDeduplicationPort;

    @MockitoBean
    private com.npick.member.domain.repository.MemberRepository memberRepository;

    @MockitoBean
    private com.npick.feedback.domain.repository.FeedbackRepository feedbackRepository;

    @MockitoBean
    private com.npick.feedback.application.query.InquiryListQuery inquiryListQuery;

    @MockitoBean
    private com.npick.feedback.application.query.InquiryDetailQuery inquiryDetailQuery;

    @MockitoBean
    private org.springframework.data.jpa.mapping.JpaMetamodelMappingContext jpaMappingContext;

    @Autowired
    private MockMvc mockMvc;

    /** 빈 배선과 설정이 깨지면 컨텍스트가 뜨지 않는다. 부팅 자체를 검증한다. */
    @Test
    void contextLoads() {}

    /** 컨텍스트가 떠도 필터가 응답을 막을 수 있다. 보안 필터나 서블릿 설정을 바꿀 때 헬스 체크가 막히지 않는지 검증한다. {@link #contextLoads()} 만으로는 이 경우를 잡지 못한다. */
    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
