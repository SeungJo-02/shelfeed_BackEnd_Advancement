package com.shelfeed.backend.domain.member.dto.request;

import com.shelfeed.backend.domain.member.enums.LibraryVisibility;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SettingsUpdateRequest {
    private Boolean likeEnabled;
    private Boolean commentEnabled;
    private Boolean followEnabled;
    private Boolean followingReviewEnabled;
    private LibraryVisibility libraryVisibility;
}
