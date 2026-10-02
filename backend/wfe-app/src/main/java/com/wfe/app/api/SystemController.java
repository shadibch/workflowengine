package com.wfe.app.api;

import com.wfe.core.port.IdentityProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Endpoints the SPA needs before it can render anything, and the fastest way to
 * verify the identity model end to end.
 *
 * <p>{@code /api/system/me} is deliberately DB-authoritative: the roles and
 * permissions it returns are the ones the server will actually enforce, not the
 * ones the token claims. If the two ever disagree, this endpoint shows the truth.
 */
@RestController
@RequestMapping("/api/system")
@Tag(name = "System", description = "Runtime metadata about this engine instance")
public class SystemController {

    private final IdentityProvider identityProvider;

    public SystemController(IdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in user, with database-resolved roles and permissions")
    public CurrentUserResponse me() {
        IdentityProvider.CurrentUser user = identityProvider.requireCurrent();
        return new CurrentUserResponse(
                user.id(),
                user.username(),
                user.email(),
                user.roles(),
                user.permissions(),
                user.tenantId(),
                user.locale(),
                user.timezone());
    }

    @GetMapping("/permissions")
    @Operation(summary = "Everything the caller may do, for nav and feature gating")
    public PermissionResponse permissions() {
        IdentityProvider.CurrentUser user = identityProvider.requireCurrent();
        return new PermissionResponse(user.permissions());
    }

    /**
     * @param roles       role codes currently held, validity window applied
     * @param permissions permission codes granted through those roles. These are
     *                    what {@code @PreAuthorize} checks, and they are read
     *                    fresh from the database on every request
     */
    public record CurrentUserResponse(Long id, String username, String email, Set<String> roles,
                                      Set<String> permissions, String tenantId, String locale,
                                      String timezone) {
    }

    public record PermissionResponse(Set<String> permissions) {
    }
}
