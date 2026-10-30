package com.linkforge.service;

import com.linkforge.dto.link.LinkResponse;
import com.linkforge.entity.Link;
import com.linkforge.exception.ShortCodeAllocationException;
import com.linkforge.support.TestProperties;
import com.linkforge.util.ShortCodeGenerator;
import com.linkforge.util.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkServiceTest {

    private static final String URL = "https://example.com/products/phones";

    @Mock
    private LinkInserter inserter;

    @Mock
    private ShortCodeGenerator codeGenerator;

    @Mock
    private UrlValidator urlValidator;

    private LinkService service;

    @BeforeEach
    void setUp() {
        service = new LinkService(
                inserter,
                codeGenerator,
                urlValidator,
                TestProperties.create(7, 2, List.of("login", "health")));
        when(urlValidator.validate(URL)).thenReturn(URL);
    }

    @Test
    void retriesWhenTheUniqueConstraintRejectsACode() {
        when(codeGenerator.generate()).thenReturn("aaaaaaa", "bbbbbbb");
        when(inserter.insert(any(Link.class)))
                .thenThrow(collision())
                .thenAnswer(invocation -> invocation.getArgument(0));

        LinkResponse response = service.create(7L, URL);

        assertThat(response.code()).isEqualTo("bbbbbbb");
        assertThat(response.originalUrl()).isEqualTo(URL);
        assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/bbbbbbb");
        verify(codeGenerator, times(2)).generate();
        verify(inserter, times(2)).insert(any(Link.class));
    }

    @Test
    void givesUpAfterTheConfiguredNumberOfCollisions() {
        when(codeGenerator.generate()).thenReturn("aaaaaaa");
        when(inserter.insert(any(Link.class))).thenThrow(collision());

        assertThatThrownBy(() -> service.create(7L, URL))
                .isInstanceOf(ShortCodeAllocationException.class)
                .hasMessageContaining("2 attempts");

        verify(inserter, times(2)).insert(any(Link.class));
    }

    @Test
    void skippedReservedAliasesDoNotConsumeAnInsertAttempt() {
        when(codeGenerator.generate()).thenReturn("login", "bbbbbbb");
        when(inserter.insert(any(Link.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LinkResponse response = service.create(7L, URL);

        assertThat(response.code()).isEqualTo("bbbbbbb");
        verify(inserter, times(1)).insert(any(Link.class));
    }

    @Test
    void doesNotRetryADifferentIntegrityViolation() {
        when(codeGenerator.generate()).thenReturn("aaaaaaa");
        DataIntegrityViolationException other = new DataIntegrityViolationException(
                "check violation",
                new SQLException("new row violates check constraint \"chk_links_url_scheme\""));
        when(inserter.insert(any(Link.class))).thenThrow(other);

        assertThatThrownBy(() -> service.create(7L, URL)).isSameAs(other);

        verify(inserter, times(1)).insert(any(Link.class));
    }

    @Test
    void storesTheAuthenticatedUsersIdAndValidatedUrl() {
        when(codeGenerator.generate()).thenReturn("aB3x9Qz");
        when(inserter.insert(any(Link.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(99L, URL);

        ArgumentCaptor<Link> captor = ArgumentCaptor.forClass(Link.class);
        verify(inserter).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(99L);
        assertThat(captor.getValue().getOriginalUrl()).isEqualTo(URL);
        assertThat(captor.getValue().isCustomAlias()).isFalse();
    }

    @Test
    void rejectsBeforeGeneratingACodeWhenTheUrlIsInvalid() {
        when(urlValidator.validate(URL)).thenThrow(new RuntimeException("invalid"));

        assertThatThrownBy(() -> service.create(7L, URL)).hasMessage("invalid");

        verify(codeGenerator, never()).generate();
        verify(inserter, never()).insert(any(Link.class));
    }

    private static DataIntegrityViolationException collision() {
        return new DataIntegrityViolationException(
                "duplicate",
                new SQLException("duplicate key value violates unique constraint \"uq_links_short_code\""));
    }
}
