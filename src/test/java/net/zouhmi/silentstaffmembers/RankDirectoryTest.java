package net.zouhmi.silentstaffmembers;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankDirectoryTest {
    @Test
    void doesNothingWhenTheSectionIsMissingOrEmpty() {
        RankDirectory directory = RankDirectory.parse(null, warning -> { });

        assertTrue(directory.isEmpty());
        assertEquals("Admin", directory.label("Admin"));
        assertEquals("#FFFFFF", directory.display(StaffRank.from("Admin", "#FFFFFF")).hexColor());
    }

    @Test
    void readsABareColourAndWarnsAboutAnythingElse() {
        List<String> warnings = new ArrayList<>();
        RankDirectory directory = parse("""
                custom-ranks:
                  Legend: '#FFAA00'
                  Oops: admins
                """, warnings);

        assertEquals("#FFAA00", directory.byLabel("legend").hexColor());
        assertEquals("Legend", directory.byLabel("Legend").name());
        assertEquals(1, warnings.size(), warnings.toString());
        // A value that is not a colour leaves the service color alone, and the rank still exists.
        assertEquals("#FFFFFF", directory.byLabel("Oops").hexColor());
        assertEquals(List.of("Legend", "Oops"), directory.labels());
    }

    @Test
    void recoloursARankTheServiceReportsWithoutRenamingIt() {
        RankDirectory directory = parse("""
                custom-ranks:
                  Senior Tech Support:
                    color: '#00AAFF'
                """, new ArrayList<>());

        StaffRank fromService = StaffRank.from("Senior Tech Support", "#123456", 40);
        StaffRank shown = directory.display(fromService);

        assertEquals("Senior Tech Support", shown.name());
        assertEquals("#00AAFF", shown.hexColor());
        // A priority of 0 means "leave the service's own ordering alone".
        assertEquals(40, shown.priority());
    }

    @Test
    void renamesARankReachableByBothTheKeyAndTheNewLabel() {
        RankDirectory directory = parse("""
                custom-ranks:
                  Head Staff:
                    label: Head of Staff
                    color: '#FF0000'
                    priority: 90
                """, new ArrayList<>());

        assertEquals(List.of("Head of Staff"), directory.labels());
        assertTrue(directory.defines("Head Staff"));
        assertTrue(directory.defines("head_of_staff"));
        assertEquals("Head of Staff", directory.byLabel("head staff").name());
        assertEquals(90, directory.byLabel("Head of Staff").priority());
        assertEquals("#FF0000", directory.display(StaffRank.from("Head Staff", "#FFFFFF")).hexColor());
    }

    @Test
    void keepsTheConfigOrderAndReportsIt() {
        RankDirectory directory = parse("""
                custom-ranks:
                  Weird:
                    color: red
                  Fine:
                    color: '#00FF00'
                  Owner:
                    priority: 5
                """, new ArrayList<>());

        assertEquals(List.of("Weird", "Fine", "Owner"), directory.labels());
        assertEquals("#FFFFFF", directory.byLabel("Weird").hexColor());
        assertEquals("#00FF00", directory.byLabel("Fine").hexColor());
        assertEquals(5, directory.byLabel("Owner").priority());
    }

    @Test
    void anUnknownLabelIsShownInTheDefaultColor() {
        RankDirectory directory = parse("""
                custom-ranks:
                  Legend: '#FFAA00'
                """, new ArrayList<>());

        assertFalse(directory.defines("Some Made Up Rank"));
        assertEquals("Some Made Up Rank", directory.byLabel("Some Made Up Rank").name());
        assertEquals("#FFFFFF", directory.byLabel("Some Made Up Rank").hexColor());
        assertEquals("", directory.label(null));
    }

    private static RankDirectory parse(String yaml, List<String> warnings) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return RankDirectory.parse(config.getConfigurationSection("custom-ranks"), warnings::add);
    }
}
