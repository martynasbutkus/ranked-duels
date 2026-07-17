package com.rankedduels;

public enum DuelState
{
    IDLE,               // nothing happening
    CHALLENGE_SENT,     // we challenged someone, waiting for their answer
    CHALLENGE_RECEIVED, // someone challenged us, prompt showing
    PENDING_FIGHT,      // both accepted, waiting for first hit
    FIGHTING            // fight in progress
}
