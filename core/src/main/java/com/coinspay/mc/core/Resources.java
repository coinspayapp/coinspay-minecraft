package com.coinspay.mc.core;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Default files shipped in the jar. */
final class Resources {
    private Resources() {
    }

    /** Copies /resource from the jar to the folder unless the file exists; returns its path. */
    static Path copyDefault(Path folder, String resource, String target) throws IOException {
        Path file = folder.resolve(target);
        if (Files.exists(file)) return file;
        Files.createDirectories(folder);
        try (InputStream in = Resources.class.getResourceAsStream("/" + resource)) {
            if (in == null) throw new IOException(resource + " is missing in the jar");
            Files.copy(in, file);
        }
        return file;
    }

    static Path copyDefault(Path folder, String name) throws IOException {
        return copyDefault(folder, name, name);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readYaml(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
            if (root == null) return null;
            if (!(root instanceof Map)) throw new IOException(file.getFileName() + " is not a YAML mapping");
            return (Map<String, Object>) root;
        } catch (RuntimeException e) {
            throw new IOException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
