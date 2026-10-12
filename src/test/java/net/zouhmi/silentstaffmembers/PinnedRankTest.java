package net.zouhmi.silentstaffmembers;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A pinned rank must keep the colour Silent Client reported for it: {@code custom-ranks}
 * wins, then the colours learned from real lookups, and only an invented rank falls back
 * to white. Quoted multi-word ranks resolve the same as bare ones.
 */
class PinnedRankTest {
    private static final StaffRank SERVICE_OWNER = StaffRank.from("Owner", "#FFAA00", 90);
    private static final StaffRank SERVICE_MOD = StaffRank.from("Client Mod", "#5d6fff", 74);

    private static Map<String, StaffRank> service(StaffRank... ranks) {
        Map<String, StaffRank> learned = new HashMap<>();
        for (StaffRank rank : ranks) learned.put(RankDirectory.normalize(rank.name()), rank);
        return learned;
    }

    private static RankDirectory directory(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return RankDirectory.parse(config.getConfigurationSection("custom-ranks"), new ArrayList<>()::add);
    }

    @Test
    void customRanksWinOverTheServiceColour() {
        RankDirectory directory = directory("""
                custom-ranks:
                  Owner: '#00AAFF'
                """);

        StaffRank pinned = SilentStaffMembersPlugin.resolvePinnedRank(
                "Owner", directory, service(SERVICE_OWNER));

        assertEquals("Owner", pinned.name());
        assertEquals("#00AAFF", pinned.hexColor());
    }

    @Test
    void aRealRankKeepsTheServiceColourWithoutAnyConfig() {
        RankDirectory directory = directory("custom-ranks: {}");

        StaffRank pinned = SilentStaffMembersPlugin.resolvePinnedRank(
                "Owner", directory, service(SERVICE_OWNER));

        assertEquals("Owner", pinned.name());
        assertEquals("#FFAA00", pinned.hexColor());
        assertEquals(90, pinned.priority());
    }

    @Test
    void serviceRanksMatchCaseAndSeparatorInsensitively() {
        RankDirectory directory = directory("custom-ranks: {}");
        Map<String, StaffRank> learned = service(SERVICE_MOD);

        assertEquals("#5d6fff", SilentStaffMembersPlugin
                .resolvePinnedRank("client mod", directory, learned).hexColor());
        assertEquals("Client Mod", SilentStaffMembersPlugin
                .resolvePinnedRank("CLIENT_MOD", directory, learned).name());
        StaffRank unknown = SilentStaffMembersPlugin.resolvePinnedRank(
                "Client Mod Manager", directory, learned);
        assertEquals("Client Mod Manager", unknown.name());
        assertEquals("#FFFFFF", unknown.hexColor());
    }

    @Test
    void anInventedRankFallsBackToWhiteButKeepsItsSpelling() {
        RankDirectory directory = directory("custom-ranks: {}");

        StaffRank pinned = SilentStaffMembersPlugin.resolvePinnedRank(
                "Head of Staff", directory, service(SERVICE_OWNER));

        assertEquals("Head of Staff", pinned.name());
        assertEquals("#FFFFFF", pinned.hexColor());
    }

    @Test
    void blankLabelsResolveToNothing() {
        assertNull(SilentStaffMembersPlugin.resolvePinnedRank(
                null, RankDirectory.empty(), service(SERVICE_OWNER)));
        assertNull(SilentStaffMembersPlugin.resolvePinnedRank(
                "   ", RankDirectory.empty(), service(SERVICE_OWNER)));
    }

    @Test
    void quotedMultiWordRanksResolveLikeBareOnes() {
        assertEquals("Client Mod", SilentStaffMembersPlugin.unquote("\"Client Mod\""));
        assertEquals("Client Mod", SilentStaffMembersPlugin.unquote("'Client Mod'"));
        assertEquals("Client Mod", SilentStaffMembersPlugin.unquote("  Client Mod  "));
        assertEquals("", SilentStaffMembersPlugin.unquote(null));
        assertEquals("\"Client", SilentStaffMembersPlugin.unquote("\"Client"));

        RankDirectory directory = directory("custom-ranks: {}");
        Map<String, StaffRank> learned = service(SERVICE_MOD);
        StaffRank pinned = SilentStaffMembersPlugin.resolvePinnedRank(
                SilentStaffMembersPlugin.unquote("\"Client Mod\""), directory, learned);
        assertEquals("Client Mod", pinned.name());
        assertEquals("#5d6fff", pinned.hexColor());
    }
}
