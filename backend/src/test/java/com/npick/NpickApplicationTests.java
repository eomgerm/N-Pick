package com.npick;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NpickApplicationTests {

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
