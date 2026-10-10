package net.zouhmi.silentstaffmembers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The icon pack is built for real here: the bundled icons are combined into an atlas,
 * wrapped in a zip and hashed, and then the zip is read back to check that a client would
 * find the pack metadata, the font provider and the exact glyph-to-cell mapping.
 */
class IconPackTest {
    private static final Logger LOGGER = Logger.getLogger("IconPackTest");
    private static final List<String> EXPECTED_ICONS = List.of(
            "admin", "client_mod", "client_mod_manager", "developer", "senior_client_mod",
            "senior_tech_support", "senior_tester", "tech_support", "tester", "tester_manager");

    @TempDir
    Path dataFolder;

    @Test
    void buildsAPackWithEveryBundledIcon() throws IOException {
        IconPack pack = IconPack.generate(LOGGER, dataFolder, IconPack.DEFAULT_PACK_FORMAT);

        assertTrue(pack.isAvailable());
        assertEquals(EXPECTED_ICONS.size(), pack.iconCount());
        assertEquals(EXPECTED_ICONS, List.copyOf(pack.iconNames()));
        assertEquals(IconPack.DEFAULT_PACK_FORMAT, pack.packFormat());
        assertTrue(Files.isRegularFile(pack.zip()));
        assertEquals(40, pack.sha1().length());
        assertEquals(pack.sha1(), sha1(Files.readAllBytes(pack.zip())));
        for (String icon : EXPECTED_ICONS) {
            assertTrue(Files.isRegularFile(dataFolder.resolve("icons").resolve(icon + ".png")),
                    "icon extracted to the data folder: " + icon);
        }
    }

    @Test
    void zipHoldsPackMetaFontProviderAndAWholeAtlas() throws IOException {
        IconPack pack = IconPack.generate(LOGGER, dataFolder, IconPack.DEFAULT_PACK_FORMAT);

        try (ZipFile zip = new ZipFile(pack.zip().toFile())) {
            assertEquals(List.of(
                    "assets/silentstaff/font/icons.json",
                    "assets/silentstaff/textures/font/icons.png",
                    "pack.mcmeta"), entryNames(zip));

            JsonObject meta = JsonParser.parseString(read(zip, "pack.mcmeta")).getAsJsonObject();
            assertEquals(IconPack.DEFAULT_PACK_FORMAT,
                    meta.getAsJsonObject("pack").get("pack_format").getAsInt());

            JsonObject provider = JsonParser.parseString(read(zip, "assets/silentstaff/font/icons.json"))
                    .getAsJsonObject().getAsJsonArray("providers").get(0).getAsJsonObject();
            assertEquals("bitmap", provider.get("type").getAsString());
            assertEquals("silentstaff:font/icons.png", provider.get("file").getAsString());

            JsonArray rows = provider.getAsJsonArray("chars");
            assertEquals(1, rows.size(), "one row of icons");
            String row = rows.get(0).getAsString();
            assertEquals(EXPECTED_ICONS.size(), row.codePointCount(0, row.length()));
            assertEquals(String.valueOf(IconPack.FIRST_GLYPH), row.substring(0, 1),
                    "the row starts at the first private-use glyph");

            // Every glyph must land on a cell that actually holds an icon, or the client
            // would draw an empty box for that rank.
            BufferedImage atlas = ImageIO.read(new ByteArrayInputStream(
                    Files.readAllBytes(unzipToTemp(zip, "assets/silentstaff/textures/font/icons.png"))));
            assertNotNull(atlas);
            int cellSize = atlas.getHeight();
            assertEquals(EXPECTED_ICONS.size() * cellSize, atlas.getWidth());
            for (int cell = 0; cell < EXPECTED_ICONS.size(); cell++) {
                assertTrue(cellHasPixels(atlas, cell, cellSize),
                        "atlas cell " + cell + " (" + EXPECTED_ICONS.get(cell) + ") is not empty");
            }
        }
    }

    @Test
    void mapsRanksToTheirOwnIconAndHonoursOverrides() {
        IconPack pack = IconPack.generate(LOGGER, dataFolder, IconPack.DEFAULT_PACK_FORMAT);

        String admin = pack.glyphFor("Admin", Map.of()).orElseThrow();
        assertEquals(String.valueOf(IconPack.FIRST_GLYPH), admin, "admin sorts first");
        assertEquals(admin, pack.glyphFor("  ADMIN ", Map.of()).orElseThrow(), "matching ignores case");
        assertEquals(pack.glyphFor("Senior Tech Support", Map.of()),
                pack.glyphFor("senior_tech_support", Map.of()));

        assertTrue(pack.glyphFor("Owner", Map.of()).isEmpty(), "no icon shipped for Owner");
        assertEquals(admin, pack.glyphFor("Owner", Map.of("owner", "admin")).orElseThrow());
        assertEquals(admin, pack.glyphFor("Senior Admin", Map.of("senior admin", "admin")).orElseThrow());
        assertTrue(pack.glyphFor(null, Map.of()).isEmpty());
        assertNotEquals(pack.glyphFor("Admin", Map.of()), pack.glyphFor("Tester", Map.of()));
    }

    @Test
    void rebuildingDoesNotChangeAnUnchangedPack() throws IOException {
        IconPack first = IconPack.generate(LOGGER, dataFolder, IconPack.DEFAULT_PACK_FORMAT);
        byte[] bytes = Files.readAllBytes(first.zip());

        IconPack second = IconPack.generate(LOGGER, dataFolder, IconPack.DEFAULT_PACK_FORMAT);

        assertEquals(first.sha1(), second.sha1(), "the zip is byte-stable, so clients keep the cached pack");
        assertEquals(bytes.length, Files.readAllBytes(second.zip()).length);
    }

    @Test
    void acceptsAnAdminsOwnIconAndSkipsBrokenFiles() throws IOException {
        Files.createDirectories(dataFolder.resolve("icons"));
        Files.writeString(dataFolder.resolve("icons").resolve("broken.png"), "not a png");
        writeIcon(dataFolder.resolve("icons").resolve("owner.png"), 12);

        IconPack pack = IconPack.generate(LOGGER, dataFolder, IconPack.DEFAULT_PACK_FORMAT);

        assertEquals(EXPECTED_ICONS.size() + 1, pack.iconCount());
        assertTrue(pack.glyphFor("Owner", Map.of()).isPresent(), "a dropped-in icon is picked up");
        assertFalse(pack.iconNames().contains("broken"));
    }

    @Test
    void staysUnavailableWhenTheIconsFolderCannotBeUsed() throws IOException {
        // A plain file where the icons directory belongs: generation must fail safely
        // rather than take the plugin down with it.
        Files.writeString(dataFolder.resolve("icons"), "not a directory");

        IconPack pack = IconPack.generate(LOGGER, dataFolder, IconPack.DEFAULT_PACK_FORMAT);

        assertFalse(pack.isAvailable());
        assertEquals(0, pack.iconCount());
        assertTrue(pack.glyphFor("Admin", Map.of()).isEmpty());
        assertEquals(IconPack.DEFAULT_PACK_FORMAT, pack.packFormat());
    }

    private static void writeIcon(Path file, int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, 0xFF00FF00);
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }

    private static boolean cellHasPixels(BufferedImage atlas, int cell, int cellSize) {
        for (int x = cell * cellSize; x < (cell + 1) * cellSize; x++) {
            for (int y = 0; y < cellSize; y++) {
                if ((atlas.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }

    private static List<String> entryNames(ZipFile zip) {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
        names.sort(String::compareTo);
        return names;
    }

    private static String read(ZipFile zip, String name) throws IOException {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private Path unzipToTemp(ZipFile zip, String name) throws IOException {
        Path target = dataFolder.resolve("read-back.png");
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static String sha1(byte[] content) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
