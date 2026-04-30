package com.shelfeed.backend.domain.auth.dto.internal;

import com.shelfeed.backend.domain.auth.dto.response.GoogleLoginResponse;
import com.shelfeed.backend.domain.auth.dto.response.LoginResponse;
import com.shelfeed.backend.domain.auth.dto.response.SignupResponse;
import com.shelfeed.backend.domain.auth.dto.response.TokenRefreshResponse;

public class AuthTokenResult {
    public record Signup(SignupResponse response, String refreshToken) {}
    public record Login(LoginResponse response, String refreshToken) {}
    public record GoogleLogin(GoogleLoginResponse response, String refreshToken) {}
    public record Refresh(TokenRefreshResponse response, String newRefreshToken) {}
}
