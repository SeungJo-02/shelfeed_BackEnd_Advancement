package com.shelfeed.backend.domain.member.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.enums.LibraryVisibility;
import com.shelfeed.backend.domain.member.vo.NotificationPreferences;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SettingsResponse {

    @JsonProperty("likeEnabled")
    private boolean likeEnabled;
    @JsonProperty("commentEnabled")
    private boolean commentEnabled;
    @JsonProperty("followEnabled")
    private boolean followEnabled;
    @JsonProperty("followingReviewEnabled")
    private boolean followingReviewEnabled;
    private LibraryVisibility libraryVisibility;

    public static SettingsResponse of(Member member) {
        NotificationPreferences prefs = member.getNotificationPreferences();
        return SettingsResponse.builder()
                .likeEnabled(prefs.isLikeEnabled())
                .commentEnabled(prefs.isCommentEnabled())
                .followEnabled(prefs.isFollowEnabled())
                .followingReviewEnabled(prefs.isFollowingReviewEnabled())
                .libraryVisibility(member.getLibraryVisibility())
                .build();
    }
}
