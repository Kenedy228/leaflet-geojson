package com.github.gis.security;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class EditingAccess {
    private final boolean enabled;
    private final String username;
    private final String passwordHash;

    public EditingAccess(@Value("${app.editing.enabled:false}") boolean enabled,
                         @Value("${app.editor.username:editor}") String username,
                         @Value("${app.editor.password:}") String password) {
        this.enabled = enabled;
        this.username = username;
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (enabled && (username.isBlank() || username.length() > 64
                || username.chars().anyMatch(Character::isWhitespace) || bytes < 16 || bytes > 72)) {
            throw new IllegalArgumentException(
                    "Editing requires EDITOR_USERNAME and an EDITOR_PASSWORD of 16 to 72 UTF-8 bytes");
        }
        passwordHash = enabled ? new BCryptPasswordEncoder(12).encode(password) : null;
    }

    public boolean enabled() { return enabled; }
    public String username() { return username; }
    public String passwordHash() { return passwordHash; }
}
