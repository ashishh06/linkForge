package com.linkforge.util;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class Base62Test {

    @Test
    void encodesZeroAndKnownValues() {
        assertThat(Base62.encode(0)).isEqualTo("0");
        assertThat(Base62.encode(10)).isEqualTo("A");
        assertThat(Base62.encode(61)).isEqualTo("z");
        assertThat(Base62.encode(62)).isEqualTo("10");
        assertThat(Base62.encode(62 * 62)).isEqualTo("100");
    }

    @Test
    void alphabetHasSixtyTwoUniqueCharacters() {
        assertThat(Base62.ALPHABET).hasSize(62);
        assertThat(Base62.ALPHABET.chars().distinct().count()).isEqualTo(62);
    }

    @Test
    void rejectsNegativeValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> Base62.encode(-1));
    }

    @Test
    void randomCodesHaveRequestedLengthAndAlphabet() {
        SecureRandom random = new SecureRandom();
        Set<String> codes = new HashSet<>();

        for (int i = 0; i < 5_000; i++) {
            String code = Base62.random(7, random);
            assertThat(code).hasSize(7);
            assertThat(Base62.isBase62(code)).isTrue();
            codes.add(code);
        }

        // 62^7 possibilities: 5,000 samples colliding would indicate a broken source.
        assertThat(codes).hasSize(5_000);
    }

    @Test
    void recognisesBase62Strings() {
        assertThat(Base62.isBase62("aB3x9Qz")).isTrue();
        assertThat(Base62.isBase62("abc-123")).isFalse();
        assertThat(Base62.isBase62("")).isFalse();
        assertThat(Base62.isBase62(null)).isFalse();
    }
}
