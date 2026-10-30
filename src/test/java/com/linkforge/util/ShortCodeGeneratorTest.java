package com.linkforge.util;

import com.linkforge.support.TestProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {

    @Test
    void generatesConfiguredLengthFromBase62Alphabet() {
        ShortCodeGenerator generator = new ShortCodeGenerator(TestProperties.defaults());

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String code = generator.generate();
            assertThat(code).hasSize(7);
            assertThat(Base62.isBase62(code)).isTrue();
            assertThat(seen.add(code)).isTrue();
        }
    }

    @Test
    void honoursConfiguredCodeLength() {
        ShortCodeGenerator generator = new ShortCodeGenerator(
                TestProperties.create(12, 5, TestProperties.defaults().link().reservedAliases()));

        assertThat(generator.generate()).hasSize(12);
    }
}
