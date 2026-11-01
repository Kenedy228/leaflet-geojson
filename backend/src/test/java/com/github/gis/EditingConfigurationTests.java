package com.github.gis;

import com.github.gis.security.EditingAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class EditingConfigurationTests {
    @ParameterizedTest
    @ValueSource(strings = {"", "short", "123456789012345", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void enablingEditingRequiresAnExplicitBcryptCompatiblePassword(String password) {
        assertThatIllegalArgumentException().isThrownBy(() -> new EditingAccess(true, "editor", password))
                .withMessage("Editing requires EDITOR_USERNAME and an EDITOR_PASSWORD of 16 to 72 UTF-8 bytes");
    }

    @Test
    void readonlyModeNeedsNoPasswordAndCreatesNoPasswordHash() {
        var access = new EditingAccess(false, "editor", "");
        assertThat(access.enabled()).isFalse();
        assertThat(access.passwordHash()).isNull();
    }
}
