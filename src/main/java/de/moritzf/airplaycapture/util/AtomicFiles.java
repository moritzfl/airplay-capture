package de.moritzf.airplaycapture.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Replaces a state file only after writing the complete new contents next to it. */
public final class AtomicFiles {

    private AtomicFiles() {}

    public static void writeString(Path target, String contents) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".airplay-", ".tmp");
        try {
            Files.writeString(temporary, contents);
            Files.move(
                temporary,
                absolute,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
