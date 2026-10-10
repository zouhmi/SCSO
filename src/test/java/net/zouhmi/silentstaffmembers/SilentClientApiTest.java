package net.zouhmi.silentstaffmembers;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SilentClientApiTest {
    @Test
    void parsesServiceRankNamesAndColors() throws Exception {
        SilentClientApi api = new SilentClientApi("https://api.silentclient.net",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                RankPolicy.DEFAULT_STAFF_RANKS, 0, 10);
        try {
            Method method = SilentClientApi.class.getDeclaredMethod("parseRanks", String.class,
                    com.google.gson.JsonObject.class);
            method.setAccessible(true);
            SilentClientApi.LookupResult result = (SilentClientApi.LookupResult) method.invoke(api,
                    "Tester", JsonParser.parseString("""
                            {"ranks":[{"name":"Senior Tech Support","key":"senior_tech_support","meta":{"color":"#CC00FF"}},
                             {"name":"Player","key":"player","meta":{"color":"#616161"}}]}
                            """).getAsJsonObject());
            assertTrue(result.isStaff());
            assertEquals(1, result.staffRanks().size());
            assertEquals("Senior Tech Support", result.displayRank().name());
            assertEquals("#CC00FF", result.displayRank().hexColor());
        } finally {
            api.close();
        }
    }

    @Test
    void picksTheHighestPriorityStaffRankWhenSeveralAreHeld() throws Exception {
        SilentClientApi api = new SilentClientApi("https://api.silentclient.net",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                RankPolicy.DEFAULT_STAFF_RANKS, 0, 10);
        try {
            Method method = SilentClientApi.class.getDeclaredMethod("parseRanks", String.class,
                    com.google.gson.JsonObject.class);
            method.setAccessible(true);
            SilentClientApi.LookupResult result = (SilentClientApi.LookupResult) method.invoke(api,
                    "Zouhmi", JsonParser.parseString("""
                            {"ranks":[
                              {"name":"Senior Tech Support","key":"senior_tech_support","priority":76,"meta":{"color":"#CC00FF"}},
                              {"name":"Tech Support","key":"tech_support","priority":74,"meta":{"color":"#5d6fff"}},
                              {"name":"Tester","key":"tester","priority":20,"meta":{"color":"#67d98a"}}
                            ]}
                            """).getAsJsonObject());
            assertEquals(3, result.staffRanks().size());
            assertEquals("Senior Tech Support", result.displayRank().name());
            assertEquals("#CC00FF", result.displayRank().hexColor());
        } finally {
            api.close();
        }
    }

    @Test
    void rejectsPlayerRankAndInvalidColorDoesNotEscapeAsFormat() throws Exception {
        SilentClientApi api = new SilentClientApi("https://api.silentclient.net",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                RankPolicy.DEFAULT_STAFF_RANKS, 0, 10);
        try {
            Method method = SilentClientApi.class.getDeclaredMethod("parseRanks", String.class,
                    com.google.gson.JsonObject.class);
            method.setAccessible(true);
            SilentClientApi.LookupResult result = (SilentClientApi.LookupResult) method.invoke(api,
                    "Tester", JsonParser.parseString("""
                            {"ranks":[{"name":"Player","key":"player","meta":{"color":"red;op=true"}}]}
                            """).getAsJsonObject());
            assertFalse(result.isStaff());
            assertTrue(StaffRank.from("Tester", "invalid").hexColor().equals("#FFFFFF"));
        } finally {
            api.close();
        }
    }
}
