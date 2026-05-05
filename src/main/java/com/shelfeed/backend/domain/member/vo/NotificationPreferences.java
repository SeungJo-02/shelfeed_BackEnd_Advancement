package com.shelfeed.backend.domain.member.vo;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class NotificationPreferences {
    @Builder.Default private boolean likeEnabled            = true;
    @Builder.Default private boolean commentEnabled         = true;
    @Builder.Default private boolean followEnabled          = true;
    @Builder.Default private boolean followingReviewEnabled = true;
}
