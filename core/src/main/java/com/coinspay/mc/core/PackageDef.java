package com.coinspay.mc.core;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** A product from config.yml. */
public final class PackageDef {
    public final String id;
    /** Name with &-codes, as shown in the menu and chat. */
    public final String name;
    public final List<String> description;
    public final String icon;
    public final BigDecimal price;
    public final String currency;
    public final boolean requireOnline;
    public final List<String> commands;
    /** 0 for a permanent product. */
    public final long durationMillis;
    public final List<String> expireCommands;

    public PackageDef(String id, String name, List<String> description, String icon, BigDecimal price, String currency,
                      boolean requireOnline, List<String> commands, long durationMillis, List<String> expireCommands) {
        this.id = id;
        this.name = name;
        this.description = Collections.unmodifiableList(description);
        this.icon = icon;
        this.price = price;
        this.currency = currency;
        this.requireOnline = requireOnline;
        this.commands = Collections.unmodifiableList(commands);
        this.durationMillis = durationMillis;
        this.expireCommands = Collections.unmodifiableList(expireCommands);
    }

    /** The name without colour codes: the invoice title. */
    public String plainName() {
        return Text.strip(name);
    }
}
