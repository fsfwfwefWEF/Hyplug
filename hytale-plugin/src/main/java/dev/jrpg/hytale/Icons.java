package dev.jrpg.hytale;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Какие иконки есть в asset pack мода (список data/icons.txt пишет tools/gen_icons.py). */
final class Icons {
    private static final Set<String> KNOWN = load();

    private Icons() {
    }

    static boolean exists(String key) {
        return KNOWN.contains(key);
    }

    private static Set<String> load() {
        Set<String> out = new HashSet<>();
        try (InputStream in = Icons.class.getResourceAsStream("/data/icons.txt")) {
            if (in == null) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.isBlank()) out.add(line.trim());
            }
        } catch (Exception ignored) {
            // без списка иконки просто не показываются
        }
        return out;
    }
}
