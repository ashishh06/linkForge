package com.linkforge.util;

import com.linkforge.exception.BadRequestException;
import com.linkforge.repository.BlockedDomainRepository;
import com.linkforge.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlValidatorTest {

    @Mock
    private BlockedDomainRepository blockedDomains;

    private UrlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UrlValidator(TestProperties.defaults(), blockedDomains);
        lenient().when(blockedDomains.findAllDomains()).thenReturn(List.of());
    }

    @Test
    void acceptsHttpAndHttpsAndTrims() {
        assertDoesNotThrow(() -> validator.validate("https://example.com/products/phones?id=84729"));
        assertDoesNotThrow(() -> validator.validate("http://example.com"));
        assertDoesNotThrow(() -> validator.validate("HTTP://EXAMPLE.COM/path"));
        assertThat(validator.validate("  https://example.com/a  ")).isEqualTo("https://example.com/a");
    }

    @Test
    void acceptsPublicIpLiterals() {
        assertDoesNotThrow(() -> validator.validate("http://8.8.8.8/"));
        assertDoesNotThrow(() -> validator.validate("http://172.32.0.1/"));
        assertDoesNotThrow(() -> validator.validate("http://100.128.0.1/"));
    }

    @Test
    void rejectsBlankMissingAndOversizedInput() {
        assertBadRequest(null);
        assertBadRequest("   ");
        assertBadRequest("https://");
        assertBadRequest("https://example.com/" + "a".repeat(2048));
    }

    @Test
    void rejectsSchemesOtherThanHttpAndHttps() {
        assertBadRequest("ftp://example.com/file");
        assertBadRequest("javascript:alert(1)");
        assertBadRequest("data:text/html,<script>alert(1)</script>");
        assertBadRequest("//example.com/no-scheme");
    }

    @Test
    void rejectsUserInfo() {
        assertBadRequest("http://evil.com@trusted.com/");
        assertBadRequest("https://user:password@example.com/");
    }

    @Test
    void rejectsLoopbackAndPrivateIpv4() {
        assertBadRequest("http://localhost/");
        assertBadRequest("http://localhost./");
        assertBadRequest("http://foo.localhost/");
        assertBadRequest("http://127.0.0.1/");
        assertBadRequest("http://127.0.0.1./");
        assertBadRequest("http://127.1.2.3/");
        assertBadRequest("http://10.0.0.1/");
        assertBadRequest("http://172.16.0.1/");
        assertBadRequest("http://172.31.255.255/");
        assertBadRequest("http://192.168.1.1/");
        assertBadRequest("http://169.254.169.254/latest/meta-data/");
        assertBadRequest("http://100.64.0.1/");
        assertBadRequest("http://0.0.0.0/");
        assertBadRequest("http://224.0.0.1/");
    }

    @Test
    void rejectsDecimalIpv4Notation() {
        // Browsers treat this as 127.0.0.1.
        assertBadRequest("http://2130706433/");
    }

    @Test
    void rejectsLoopbackAndPrivateIpv6() {
        assertBadRequest("http://[::1]/");
        assertBadRequest("http://[::]/");
        assertBadRequest("http://[fd00::1]/");
        assertBadRequest("http://[fe80::1]/");
        assertBadRequest("http://[::ffff:127.0.0.1]/");
    }

    @Test
    void rejectsInvalidPort() {
        assertBadRequest("https://example.com:99999/");
    }

    @Test
    void appliesSuffixMatchedBlocklist() {
        when(blockedDomains.findAllDomains()).thenReturn(List.of("evil.com"));

        assertBadRequest("https://evil.com/");
        assertBadRequest("https://a.evil.com/");
        assertBadRequest("https://evil.com./");
        assertDoesNotThrow(() -> validator.validate("https://notevil.com/"));
    }

    @Test
    void reportsWhyTheUrlWasRejected() {
        assertThatThrownBy(() -> validator.validate("javascript:alert(1)"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("http or https");
    }

    private void assertBadRequest(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .as("url: %s", url)
                .isInstanceOf(BadRequestException.class);
    }
}
