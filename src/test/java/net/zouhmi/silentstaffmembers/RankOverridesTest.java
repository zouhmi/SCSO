package net.zouhmi.silentstaffmembers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankOverridesTest {
    private static final Logger LOGGER = Logger.getLogger("RankOverridesTest");

    @TempDir
    Path directory;

    private RankOverrides overrides() {
        return new RankOverrides(directory.resolve("ranks.yml"), LOGGER);
    }

    @Test
    void createsTheFileOnFirstLoad() throws IOException {
        RankOverrides overrides = overrides();

        overrides.load(Map.of());

        assertEquals(0, overrides.size());
        assertTrue(Files.isRegularFile(directory.resolve("ranks.yml")));
        assertTrue(Files.readString(directory.resolve("ranks.yml")).contains("players:"));
    }

    @Test
    void pinsRanksCaseInsensitivelyAndReportsChanges() {
        RankOverrides overrides = overrides();
        overrides.load(Map.of());

        assertTrue(overrides.set("Zouhir", "Senior Tech Support"));
        assertEquals("Senior Tech Support", overrides.rankFor("zouhir"));
        assertEquals("Senior Tech Support", overrides.rankFor("  ZOUHIR  "));
        assertNull(overrides.rankFor("Someone"));

        // The same pin again is not a change; a different rank is.
        assertFalse(overrides.set("zouhir", "Senior Tech Support"));
        assertTrue(overrides.set("ZOUHIR", "Owner"));
        assertEquals(1, overrides.size());
        assertEquals("Owner", overrides.rankFor("Zouhir"));
    }

    @Test
    void roundTripsThroughTheFileKeepingTheOrderAndSpelling() {
        RankOverrides overrides = overrides();
        overrides.load(Map.of());
        overrides.set("Notch", "Head of Staff");
        overrides.set("jeb_", "Admin");
        overrides.save();

        RankOverrides reloaded = overrides();
        reloaded.load(Map.of());

        assertEquals(List.of("Notch", "jeb_"), reloaded.names());
        assertEquals("Head of Staff", reloaded.rankFor("notch"));
        assertEquals(List.of("Head of Staff", "Admin"), reloaded.labels());

        assertTrue(reloaded.remove("JEB_"));
        assertFalse(reloaded.remove("jeb_"));
        assertNull(reloaded.rankFor("jeb_"));

        reloaded.save();
        RankOverrides again = overrides();
        again.load(Map.of());
        assertEquals(List.of("Notch"), again.names());
    }

    @Test
    void configEntriesAreLoadedAndTheFileWins() throws IOException {
        // A hand-edited file, in both shapes an admin tends to write.
        Files.writeString(directory.resolve("ranks.yml"), """
                players:
                  Notch: Head of Staff
                  jeb_:
                    rank: Owner
                """);
        RankOverrides overrides = overrides();

        overrides.load(Map.of("Configured", "Admin", "Notch", "Tester"));

        assertEquals("Head of Staff", overrides.rankFor("Notch"));
        assertEquals("Owner", overrides.rankFor("jeb_"));
        assertEquals("Admin", overrides.rankFor("configured"));
        assertEquals(3, overrides.size());
    }

    @Test
    void staysUsableWhenTheFileCannotBeReadOrWritten() {
        Path directoryAsFile = directory.resolve("not-a-file");
        assertTrue(directoryAsFile.toFile().mkdirs());
        RankOverrides overrides = new RankOverrides(directoryAsFile, LOGGER);

        overrides.load(Map.of("Notch", "Admin"));
        assertTrue(overrides.set("jeb_", "Owner"));
        overrides.save();

        // The config entry survives; the one that could not be written is still in memory.
        assertEquals("Admin", overrides.rankFor("notch"));
        assertEquals(2, overrides.size());
    }

    @Test
    void ignoresEntriesThatAreNotUsable() {
        RankOverrides overrides = overrides();

        overrides.load(Map.of("", "Admin", "Notch", "   "));

        assertEquals(0, overrides.size());
        assertFalse(overrides.set("  ", "Admin"));
        assertFalse(overrides.set("Notch", " "));
    }

    @Test
    void readsTheConfigSectionShapeCommandStyle() {
        org.bukkit.configuration.file.YamlConfiguration config =
                new org.bukkit.configuration.file.YamlConfiguration();
        config.set("rank-overrides.Notch", "Owner");
        config.set("rank-overrides.jeb_", "   ");
        config.set("rank-overrides.removed", null);

        Map<String, String> configured = RankOverrides.readConfigured(
                config.getConfigurationSection("rank-overrides"));

        assertEquals(Map.of("Notch", "Owner"), configured);
    }
}
