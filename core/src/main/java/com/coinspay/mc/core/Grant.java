package com.coinspay.mc.core;

import java.util.ArrayList;
import java.util.List;

/** A product bought for a time: expire-commands run when it ends, online or not. */
public final class Grant {
    public String playerId;
    public String playerName;
    public String packageId;
    public String packageName;
    public long expiresAt;
    public List<String> expireCommands = new ArrayList<>();
    public String lastOrder;
}
