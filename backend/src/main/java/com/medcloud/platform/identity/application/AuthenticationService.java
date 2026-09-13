package com.medcloud.platform.identity.application;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.api.CurrentUserResponse;
import com.medcloud.platform.identity.api.LoginResponse;
import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.domain.UserStatus;
import com.medcloud.platform.identity.infrastructure.UserAccountRepository;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthenticationService(UserAccountRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(String username, String password) {
        UserAccount account = users.findByUsername(username)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .filter(user -> passwordEncoder.matches(password, user.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        CurrentUser current = new CurrentUser(
                account.getId(), account.getUsername(), account.getRole(), account.getTenantId());
        return new LoginResponse(jwtService.issue(current), CurrentUserResponse.from(current));
    }
}
