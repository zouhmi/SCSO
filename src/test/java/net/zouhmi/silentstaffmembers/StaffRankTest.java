package net.zouhmi.silentstaffmembers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffRankTest {
    @Test
    void stripsFormattingCharactersFromServiceSuppliedNames() {
        StaffRank rank = StaffRank.from("Ad&lmin\u00A7c", "#112233");
        assertEquals("Admin", rank.name());
        assertFalse(NameFormats.tabRank(rank).contains("&l"));
        assertFalse(NameFormats.tabRank(rank).contains("\u00A7"));
    }

    @Test
    void fallsBackWhenTheNameIsUnusable() {
        assertEquals("Staff", StaffRank.from("&&&\u00A7", "#112233").name());
        assertEquals("Staff", StaffRank.from(null, "#112233").name());
        assertEquals("Staff", StaffRank.from("   ", "#112233").name());
    }

    @Test
    void fallsBackToWhiteForMalformedColors() {
        assertEquals("#FFFFFF", StaffRank.from("Admin", "red;op=true").hexColor());
        assertEquals("#FFFFFF", StaffRank.from("Admin", null).hexColor());
        assertEquals("#0A0B0C", StaffRank.from("Admin", "#0A0B0C").hexColor());
    }

    @Test
    void keepsOnlyTheServiceColorInTabText() {
        assertEquals("&#FF55FF[Admin]", NameFormats.tabRank(StaffRank.from("Admin", "#FF55FF")));
    }

    @Test
    void showsTheHighestPriorityRankAndKeepsTheServiceOrderOnTies() {
        SilentClientApi.LookupResult result = new SilentClientApi.LookupResult("Zouhmi", List.of(
                StaffRank.from("Tech Support", "#5d6fff", 74),
                StaffRank.from("Senior Tech Support", "#CC00FF", 76),
                StaffRank.from("Tester", "#67d98a", 20)));
        assertEquals("Senior Tech Support", result.displayRank().name());

        SilentClientApi.LookupResult tied = new SilentClientApi.LookupResult("Zouhmi", List.of(
                StaffRank.from("Admin", "#FFAA00", 90),
                StaffRank.from("Developer", "#AA00FF", 90)));
        assertEquals("Admin", tied.displayRank().name());
    }

    @Test
    void fallsBackToAPlainStaffRankWhenNothingMatched() {
        SilentClientApi.LookupResult empty = new SilentClientApi.LookupResult("Zouhmi", List.of());
        assertFalse(empty.isStaff());
        assertEquals("Staff", empty.displayRank().name());
        assertTrue(StaffRank.from("Admin", "#FFAA00").priority() == 0);
    }
}
