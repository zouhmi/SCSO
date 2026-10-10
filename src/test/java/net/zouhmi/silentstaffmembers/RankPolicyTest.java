package net.zouhmi.silentstaffmembers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankPolicyTest {
    private final RankPolicy policy = new RankPolicy(RankPolicy.DEFAULT_STAFF_RANKS);

    @Test
    void allowsEveryConfiguredStaffRankByNameAndKey() {
        assertTrue(policy.allows("Tester", "tester"));
        assertTrue(policy.allows("Tech Support", "tech_support"));
        assertTrue(policy.allows("Senior Tech Support", "senior_tech_support"));
        assertTrue(policy.allows("Admin", "admin"));
        assertTrue(policy.allows("Developer", "developer"));
        assertTrue(policy.allows("Senior Tester", "senior_tester"));
        assertTrue(policy.allows("Senior Admin", "senior_admin"));
        assertTrue(policy.allows("Client Mod", "client_mod"));
        assertTrue(policy.allows("Senior Client Mod", "senior_client_mod"));
        assertTrue(policy.allows("Tester Manager", "tester_manager"));
        assertTrue(policy.allows("Client Mod Manager", "client_mod_manager"));
        assertTrue(policy.allows("Owner", "owner"));
    }

    @Test
    void shipsAllTwelveStaffRanksByDefault() {
        assertEquals(12, RankPolicy.DEFAULT_STAFF_RANKS.size());
        assertTrue(RankPolicy.DEFAULT_STAFF_RANKS.containsAll(java.util.List.of(
                "Tester", "Senior Tester", "Tech Support", "Senior Tech Support", "Client Mod",
                "Senior Client Mod", "Tester Manager", "Client Mod Manager", "Admin",
                "Senior Admin", "Developer", "Owner")));
        assertFalse(policy.allows("Moderator", "moderator"));
    }

    @Test
    void comparesCaseInsensitivelyAndNormalizesSeparators() {
        assertTrue(policy.allows("  sEnIoR_tEcH-sUpPoRt  ", null));
        assertTrue(policy.allows(null, "CLIENT_MOD"));
    }

    @Test
    void doesNotAllowOrdinaryPlayerOrSubstringRanks() {
        assertFalse(policy.allows("Player", "player"));
        assertFalse(policy.allows("Admin Assistant", "admin_assistant"));
        assertFalse(policy.allows(null, null));
    }

    @Test
    void formatsKnownKeysWhenTheApiOmitsDisplayName() {
        org.junit.jupiter.api.Assertions.assertEquals(
                "Senior Tech Support", RankPolicy.displayName(null, "senior_tech_support"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "Client Mod", RankPolicy.displayName("  ", "client_mod"));
    }
}
