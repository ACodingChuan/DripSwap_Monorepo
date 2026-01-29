package com.dripswap.bff.repository;

import java.time.Instant;

public class UserIpSnapshot {
    private int userClaimCount;
    private Instant lastClaimAt;
    private int ipClaimCount;
    private boolean blocked;

    public int getUserClaimCount() {
        return userClaimCount;
    }

    public void setUserClaimCount(int userClaimCount) {
        this.userClaimCount = userClaimCount;
    }

    public Instant getLastClaimAt() {
        return lastClaimAt;
    }

    public void setLastClaimAt(Instant lastClaimAt) {
        this.lastClaimAt = lastClaimAt;
    }

    public int getIpClaimCount() {
        return ipClaimCount;
    }

    public void setIpClaimCount(int ipClaimCount) {
        this.ipClaimCount = ipClaimCount;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }
}
