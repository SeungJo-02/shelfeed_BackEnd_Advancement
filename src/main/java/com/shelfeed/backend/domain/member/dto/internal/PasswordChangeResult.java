package com.shelfeed.backend.domain.member.dto.internal;

public record PasswordChangeResult(String accessToken, String refreshToken, long accessTokenExpiresIn) {}
