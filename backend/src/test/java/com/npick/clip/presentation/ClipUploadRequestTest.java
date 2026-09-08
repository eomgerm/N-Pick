package com.npick.clip.presentation;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.UploadClipCommand;
import com.npick.clip.application.command.register.UploadClipUseCase;
import com.npick.clip.application.error.VideoPreparationErrorCode;
import com.npick.clip.presentation.controller.ClipRegistrationController;
import com.npick.common.error.BusinessException;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;
import com.npick.common.error.handler.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClipRegistrationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ErrorTypeHttpStatusMapper.class})
class ClipUploadRequestTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UploadClipUseCase upload;

    private UploadClipCommand received;

    @BeforeEach
    void setup() {
        when(upload.upload(any())).thenAnswer(call -> {
            received = call.getArgument(0);
            return new RegisterClipResult(101, 201, "queued");
        });
    }

    @Test
    void rejectsMissingFile() throws Exception {
        mockMvc.perform(request().param("source_type", "broadcast"))
                .andExpect(status().isBadRequest())
                .andExpect(invalidField("video", "영상 파일을 첨부해 주세요."));
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        mockMvc.perform(request()
                        .param("source_type", "broadcast")
                        .file(new MockMultipartFile("video", "empty.mp4", "video/mp4", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(invalidField("videoContentPresent", "내용이 비어 있는 영상 파일은 등록할 수 없습니다."));
    }

    @Test
    void rejectsMultipleFilesInsteadOfSilentlySelectingOne() throws Exception {
        mockMvc.perform(request()
                        .param("source_type", "broadcast")
                        .file(new MockMultipartFile("video", "first.mp4", "video/mp4", new byte[] {1}))
                        .file(new MockMultipartFile("video", "second.mp4", "video/mp4", new byte[] {2})))
                .andExpect(status().isBadRequest())
                .andExpect(invalidField("video", "영상 파일은 1개만 첨부할 수 있습니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"broadcast", "archive"})
    void acceptsBothTypesWithoutOptionalFields(String sourceType) throws Exception {
        mockMvc.perform(videoRequest().param("source_type", sourceType)).andExpect(status().isCreated());
        assertThat(received.sourceType()).isEqualTo(sourceType);
        assertThat(received.title()).isNull();
        assertThat(received.broadcastDate()).isNull();
        assertThat(received.filmedDate()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingSourceType(String sourceType) throws Exception {
        var request = videoRequest();
        if (sourceType != null) {
            request.param("source_type", sourceType);
        }
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(invalidField("sourceType", "영상 종류를 선택해 주세요."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mp4", "BROADCAST"})
    void rejectsUnsupportedSourceType(String sourceType) throws Exception {
        mockMvc.perform(videoRequest().param("source_type", sourceType))
                .andExpect(status().isBadRequest())
                .andExpect(invalidField("sourceType", "영상 종류는 broadcast 또는 archive여야 합니다."));
    }

    @Test
    void preservesTitleAndIndependentDates() throws Exception {
        mockMvc.perform(videoRequest()
                        .param("source_type", "broadcast")
                        .param("title", "현장 취재 영상")
                        .param("broadcast_date", "2026-09-07")
                        .param("filmed_date", "2024-02-29"))
                .andExpect(status().isCreated());
        assertThat(received.title()).isEqualTo("현장 취재 영상");
        assertThat(received.broadcastDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(received.filmedDate()).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @ParameterizedTest
    @CsvSource({"broadcast, broadcast_date", "broadcast, filmed_date", "archive, filmed_date"})
    void acceptsOneDateWithoutCopyingItToOtherDate(String type, String field) throws Exception {
        mockMvc.perform(videoRequest().param("source_type", type).param(field, "2026-09-07"))
                .andExpect(status().isCreated());
        assertThat(received.broadcastDate())
                .isEqualTo("broadcast_date".equals(field) ? LocalDate.of(2026, 9, 7) : null);
        assertThat(received.filmedDate()).isEqualTo("filmed_date".equals(field) ? LocalDate.of(2026, 9, 7) : null);
    }

    @Test
    void rejectsBroadcastDateForArchive() throws Exception {
        mockMvc.perform(videoRequest().param("source_type", "archive").param("broadcast_date", "2026-09-07"))
                .andExpect(status().isBadRequest())
                .andExpect(invalidField("broadcastDateAllowed", "자료 영상에는 방송일을 입력할 수 없습니다."));
        verifyNoInteractions(upload);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000-01-01", "2025-02-29", "2026-04-31", "2026-13-01", "2026-9-07"})
    void rejectsInvalidCalendarDatesAndFormats(String date) throws Exception {
        mockMvc.perform(videoRequest()
                        .param("source_type", "broadcast")
                        .param("broadcast_date", date)
                        .param("filmed_date", date))
                .andExpect(status().isBadRequest())
                .andExpect(invalidField("broadcastDateValid", "방송일은 실제 존재하는 YYYY-MM-DD 날짜로 입력해 주세요."))
                .andExpect(invalidField("filmedDateValid", "촬영일은 실제 존재하는 YYYY-MM-DD 날짜로 입력해 주세요."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void treatsBlankOptionalFieldsAsAbsent(String blank) throws Exception {
        mockMvc.perform(videoRequest()
                        .param("source_type", "archive")
                        .param("title", blank)
                        .param("broadcast_date", blank)
                        .param("filmed_date", blank))
                .andExpect(status().isCreated());
        assertThat(received.title()).isNull();
        assertThat(received.broadcastDate()).isNull();
        assertThat(received.filmedDate()).isNull();
    }

    @Test
    void enforcesTitleLengthFromDatabaseSchema() throws Exception {
        String title = "가".repeat(500);
        mockMvc.perform(videoRequest().param("source_type", "broadcast").param("title", title))
                .andExpect(status().isCreated());
        assertThat(received.title()).isEqualTo(title);
        mockMvc.perform(videoRequest().param("source_type", "broadcast").param("title", title + "나"))
                .andExpect(status().isBadRequest())
                .andExpect(invalidField("title", "제목은 500자 이내로 입력해 주세요."));
    }

    @Test
    void requiresRequestKeyBeforeCallingUpload() throws Exception {
        mockMvc.perform(multipart("/api/v1/clips")
                        .file(new MockMultipartFile("video", "sample.mp4", "video/mp4", new byte[] {1}))
                        .param("source_type", "archive")
                        .param("rights_confirmed", "true"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(upload);
    }

    private org.springframework.test.web.servlet.ResultMatcher invalidField(String field, String message) {
        return result -> {
            jsonPath("$.code").value("COMM_400").match(result);
            assertThat(result.getResolvedException())
                    .isInstanceOfSatisfying(
                            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
                            error -> assertThat(error.getBeanResults())
                                    .flatExtracting(
                                            org.springframework.validation.method.ParameterErrors::getFieldErrors)
                                    .anySatisfy(failure -> {
                                        assertThat(failure.getField()).isEqualTo(field);
                                        assertThat(failure.getDefaultMessage()).isEqualTo(message);
                                    }));
        };
    }

    private MockMultipartHttpServletRequestBuilder videoRequest() {
        return request().file(new MockMultipartFile("video", "sample.mp4", "video/mp4", new byte[] {1}));
    }

    private MockMultipartHttpServletRequestBuilder request() {
        return multipart("/api/v1/clips")
                .header("Idempotency-Key", "input-test")
                .param("rights_confirmed", "true");
    }

    @ParameterizedTest
    @EnumSource(
            value = VideoPreparationErrorCode.class,
            names = {"INSPECTION_FAILED", "INSPECTION_TIMED_OUT"})
    void returnsCommonErrorResponseWithoutExposingServerPaths(VideoPreparationErrorCode error) throws Exception {
        when(upload.upload(any()))
                .thenThrow(new BusinessException(error, new java.io.IOException("/private/media/input")));
        var result = mockMvc.perform(videoRequest().param("source_type", "broadcast"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value(error.code()))
                .andExpect(jsonPath("$.message").value(error.message()))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("/private/media");
    }
}
