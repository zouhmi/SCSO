package net.zouhmi.silentstaffmembers;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NameFormatsTest {
    private final StaffRank admin = StaffRank.from("Admin", "#FF55FF");

    @Test
    void splitsTheTabFormatAroundThePlayerName() {
        NameFormats.TabSlots slots = NameFormats.tabSlots("%rank% %name%", admin);
        assertEquals("&#FF55FF[Admin] ", slots.prefix());
        assertEquals("", slots.suffix());
    }

    @Test
    void honoursAReorderedTabFormat() {
        NameFormats.TabSlots slots = NameFormats.tabSlots("%name% %rank%", admin);
        assertEquals("", slots.prefix());
        assertEquals(" &#FF55FF[Admin]", slots.suffix());
    }

    @Test
    void keepsOtherTextAndDropsMessageFromTabText() {
        NameFormats.TabSlots slots = NameFormats.tabSlots("[%rank%] %name% %message%", admin);
        assertEquals("[&#FF55FF[Admin]] ", slots.prefix());
        assertEquals(" ", slots.suffix());
        assertEquals("Zouhir &#FF55FF[Admin]",
                NameFormats.tabString("%name% %rank%", admin, "Zouhir"));
    }

    @Test
    void fallsBackToTheDefaultFormatWhenEmpty() {
        NameFormats.TabSlots slots = NameFormats.tabSlots("   ", admin);
        assertEquals("&#FF55FF[Admin] ", slots.prefix());
        assertEquals("", slots.suffix());
    }

    @Test
    void rendersTheRankInChatWithTheMessage() {
        assertEquals("[Admin] Zouhir >> hello", plain(NameFormats.apply(
                "%rank% %name% >> %message%", admin, "Zouhir",
                net.kyori.adventure.text.Component.text("hello"))));
    }

    @Test
    void rendersThePlainRankNameWithoutBrackets() {
        assertEquals("Admin Zouhir", plain(NameFormats.apply(
                "%rank_plain% %name%", admin, "Zouhir", net.kyori.adventure.text.Component.empty())));
        assertEquals("Zouhir Admin", NameFormats.tabString("%name% %rank_plain%", admin, "Zouhir"));
        // The bracketed and plain placeholders can be used together.
        assertEquals("Admin [Admin] Zouhir", plain(NameFormats.apply(
                "%rank_plain% %rank% %name%", admin, "Zouhir",
                net.kyori.adventure.text.Component.empty())));
    }

    @Test
    void leavesTheRemovedIconPlaceholderAsPlainText() {
        // The plugin strips a leftover %icon% at load time; the formatter itself is literal.
        assertEquals("%icon% [Admin]", plain(NameFormats.apply(
                "%icon% %rank%", admin, "Zouhir", net.kyori.adventure.text.Component.empty())));
    }

    @Test
    void rendersTheComponentPathWithTheServiceColor() {
        assertEquals("[Admin] Zouhir",
                plain(NameFormats.displayName(admin, "Zouhir")));
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
