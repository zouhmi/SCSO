package net.zouhmi.silentstaffmembers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffWhitelistTest {
    private static final Logger LOGGER = Logger.getLogger("StaffWhitelistTest");

    @TempDir
    Path directory;

    private StaffWhitelist whitelist() {
        return new StaffWhitelist(directory.resolve("whitelist.yml"), LOGGER);
    }

    @Test
    void createsTheFileOnFirstLoad() throws IOException {
        StaffWhitelist whitelist = whitelist();

        whitelist.load();

        assertEquals(0, whitelist.size());
        assertTrue(Files.isRegularFile(directory.resolve("whitelist.yml")));
        assertTrue(Files.readString(directory.resolve("whitelist.yml")).contains("players:"));
    }

    @Test
    void matchesNamesCaseInsensitivelyAndRejectsDuplicates() {
        StaffWhitelist whitelist = whitelist();
        whitelist.load();

        assertTrue(whitelist.add("Notch"));
        assertFalse(whitelist.add("notch"));
        assertFalse(whitelist.add("  NOTCH  "));

        assertEquals(1, whitelist.size());
        assertTrue(whitelist.contains("notch"));
        assertTrue(whitelist.contains("NOTCH"));
        assertFalse(whitelist.contains("jeb_"));
        assertFalse(whitelist.contains(null));
    }

    @Test
    void roundTripsThroughTheFileKeepingTheOrderAndSpelling() {
        StaffWhitelist whitelist = whitelist();
        whitelist.load();
        whitelist.add("Notch");
        whitelist.add("jeb_");
        whitelist.add("Dinnerbone");
        whitelist.save();

        StaffWhitelist reloaded = whitelist();
        reloaded.load();

        assertEquals(List.of("Notch", "jeb_", "Dinnerbone"), reloaded.names());
        assertTrue(reloaded.contains("dinnerbone"));

        assertTrue(reloaded.remove("JEB_"));
        assertFalse(reloaded.remove("jeb_"));
        reloaded.save();

        StaffWhitelist again = whitelist();
        again.load();
        assertEquals(List.of("Notch", "Dinnerbone"), again.names());
    }

    @Test
    void acceptsASingleHandTypedNameAndSkipsEntriesThatAreNotNames() throws IOException {
        Files.writeString(directory.resolve("whitelist.yml"), "players: Notch\n");
        StaffWhitelist single = whitelist();
        single.load();
        assertEquals(List.of("Notch"), single.names());

        Files.writeString(directory.resolve("whitelist.yml"), """
                players:
                  - Notch
                  - 42
                  - {name: jeb_}
                  - "   "
                  - Dinnerbone
                """);
        StaffWhitelist mixed = whitelist();
        mixed.load();
        assertEquals(List.of("Notch", "Dinnerbone"), mixed.names());
    }

    @Test
    void staysUsableWhenTheFileCannotBeReadOrWritten() {
        Path directoryAsFile = directory.resolve("not-a-file");
        assertTrue(directoryAsFile.toFile().mkdirs());
        StaffWhitelist whitelist = new StaffWhitelist(directoryAsFile, LOGGER);

        whitelist.load();
        assertTrue(whitelist.add("Notch"));
        whitelist.save();

        assertTrue(whitelist.contains("notch"));
        assertEquals(1, whitelist.size());
    }

    @Test
    void filtersTabCompletionOptions() {
        assertEquals(List.of("add"), StaffWhitelist.filter(List.of("add", "remove", "list"), "a"));
        // An empty argument means "everything", which is what tab completion wants.
        assertEquals(List.of("add", "remove", "list"),
                StaffWhitelist.filter(List.of("add", "remove", "list"), ""));
        assertEquals(List.of("list"), StaffWhitelist.filter(List.of("add", "remove", "list"), "li"));
        assertTrue(StaffWhitelist.filter(List.of("add"), "zzz").isEmpty());
    }
}
