package com.rankedduels.api;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ChallengeInfo
{
    private final long challengeId;
    private final String challengerName;
    private final int challengerRating;
    private final int world;
    private final double winDelta;   // rating gained on win
    private final double lossDelta;  // rating lost on loss (negative)
}
