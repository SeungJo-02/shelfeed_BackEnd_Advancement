package com.shelfeed.backend.domain.follow.dto.response;

import com.shelfeed.backend.domain.follow.entity.Follow;
import com.shelfeed.backend.domain.member.entity.Member;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FollowResponse {

    private Long followId;
    private Long followingUserId;   // 팔로우된 userId
    private int followerCount;      // 상대방의 팔로워 수 (내가 눌렀을 때 상대방은 +1이 되야해서)
    private int followingCount;     // 나의 팔로잉 수 (나의 팔로잉 수 +1)

    public static FollowResponse of(Follow follow, Member follower, Member followee) {
        return FollowResponse.builder()
                .followId(follow.getFollowId())
                .followingUserId(followee.getMemberUserId())
                .followerCount(followee.getFollowerCount())
                .followingCount(follower.getFollowingCount())
                .build();
    }
}
