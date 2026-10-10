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
    void dropsTheIconAndItsSpaceWhenTheViewerHasNoPack() {
        assertEquals("[Admin] Zouhir", plain(NameFormats.apply(
                "%icon% %rank% %name%", admin, "Zouhir", net.kyori.adventure.text.Component.empty(), "")));
    }

    @Test
    void keepsTheIconWhenTheViewerHasThePack() {
        assertEquals("\uE000 [Admin] Zouhir", plain(NameFormats.apply(
                "%icon% %rank% %name%", admin, "Zouhir", net.kyori.adventure.text.Component.empty(),
                "\uE000")));
    }

    @Test
    void splitsTheTabFormatWithTheIconInFront() {
        NameFormats.TabSlots slots = NameFormats.tabSlots("%icon% %rank% %name%", admin, "\uE000");
        assertEquals("\uE000 &#FF55FF[Admin] ", slots.prefix());

        NameFormats.TabSlots withoutIcon = NameFormats.tabSlots("%icon% %rank% %name%", admin, "");
        assertEquals("&#FF55FF[Admin] ", withoutIcon.prefix());
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
