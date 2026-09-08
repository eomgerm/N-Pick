package com.npick.clip.presentation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;
import com.npick.common.error.handler.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationErrorPrivacyTest {
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/clips", "/api/v1/search", "/api/v1/feedback"})
    void bindingAndUnexpectedErrorsDoNotLogValuesOrEchoQuerySecrets(String path) {
        var handler = new GlobalExceptionHandler(new ErrorTypeHttpStatusMapper());
        var request = new MockHttpServletRequest("POST", path);
        request.setQueryString("script_text=private-script&key=secret");
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var failure = new IllegalArgumentException("private-script secret C:\\private\\original");
            assertThat(handler.handleBadRequest(failure, request).getBody().path())
                    .isEqualTo("POST " + path);
            assertThat(handler.handleUnexpected(failure, request).getBody().path())
                    .isEqualTo("POST " + path);
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
                    .anyMatch(message ->
                            message.contains("location=com.npick.clip.presentation.RegistrationErrorPrivacyTest."));
            for (var entry : appender.list) {
                assertThat(entry.getFormattedMessage()).doesNotContain("private-script", "secret", "C:\\private");
                assertThat(entry.getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
