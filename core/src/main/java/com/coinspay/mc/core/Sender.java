package com.coinspay.mc.core;

import java.util.List;
import java.util.UUID;

/** A player or the console, as the platform sees it. */
public interface Sender {

    /** Null for the console. */
    UUID uuid();

    String name();

    /** Client language such as "ru_ru" or "en_us"; null when unknown (the console). */
    String locale();

    boolean hasPermission(String permission);

    /** A line with &-colour codes. */
    void send(String legacyText);

    /** A line made of parts, some of them clickable. */
    void send(List<Part> parts);

    /** A piece of a chat line: text with &-codes, an optional link or command and a hover text. */
    final class Part {
        public final String text;
        public final String url;
        public final String command;
        public final String hover;

        private Part(String text, String url, String command, String hover) {
            this.text = text;
            this.url = url;
            this.command = command;
            this.hover = hover;
        }

        public static Part text(String text) {
            return new Part(text, null, null, null);
        }

        public static Part link(String text, String url, String hover) {
            return new Part(text, url, null, hover);
        }

        public static Part command(String text, String command, String hover) {
            return new Part(text, null, command, hover);
        }
    }
}
