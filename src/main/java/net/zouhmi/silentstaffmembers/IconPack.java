package net.zouhmi.silentstaffmembers;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The chat-icon resource pack.
 *
 * <p>Minecraft can only draw an image in chat if the client has a font that maps a
 * character to it, so the bundled Silent Client rank icons are combined into one bitmap
 * atlas, described by a font provider, wrapped in a zip and hashed. The result is written
 * next to the config, where an admin can host it and, if they want, drop extra
 * {@code icons/<name>.png} files in to extend it.
 *
 * <p>Every glyph lives in the private-use area (U+E000 onwards) so it can never collide
 * with ordinary text.
 */
final class IconPack {
    /** Resource pack format for Minecraft 26.1.2, read from that version's own version.json. */
    static final int DEFAULT_PACK_FORMAT = 84;
    static final char FIRST_GLYPH = '\uE000';
    private static final int CELL_SIZE = 64;
    /** Drawn size of a glyph in font pixels, and how far it reaches above the baseline. */
    private static final int GLYPH_HEIGHT = 9;
    private static final int GLYPH_ASCENT = 8;
    private static final String NAMESPACE = "silentstaff";
    private static final String ZIP_FILE_NAME = "SCSO-Icons.zip";
    private static final String FONT_FILE = "assets/" + NAMESPACE + "/font/icons.json";
    private static final String ATLAS_FILE = "assets/" + NAMESPACE + "/textures/font/icons.png";
    private static final long FIXED_ZIP_TIME = 315532800000L; // 1980-01-01, so identical icons give an identical zip
    /** Shipped icons, named after the rank they belong to. */
    private static final List<String> BUNDLED_ICONS = List.of(
            "admin", "client_mod", "client_mod_manager", "developer", "senior_client_mod",
            "senior_tech_support", "senior_tester", "tech_support", "tester", "tester_manager");

    private final Map<String, Character> glyphs;
    private final Path zip;
    private final String sha1;
    private final int packFormat;

    private IconPack(Map<String, Character> glyphs, Path zip, String sha1, int packFormat) {
        this.glyphs = glyphs;
        this.zip = zip;
        this.sha1 = sha1;
        this.packFormat = packFormat;
    }

    private static IconPack unavailable(int packFormat) {
        return new IconPack(Map.of(), null, "", packFormat);
    }

    /**
     * Builds (or rebuilds) the pack. Never throws: a failure leaves the icons hidden while
     * the rest of the plugin keeps working.
     */
    static IconPack generate(Logger logger, Path dataFolder, int packFormat) {
        Path iconsDirectory = dataFolder.resolve("icons");
        Path packDirectory = dataFolder.resolve("resourcepack");
        Path zip = dataFolder.resolve(ZIP_FILE_NAME);
        try {
            extractBundledIcons(logger, iconsDirectory);
            Map<String, BufferedImage> icons = readIcons(logger, iconsDirectory);
            if (icons.isEmpty()) {
                logger.warning("No usable icon PNGs in " + iconsDirectory + "; chat icons stay hidden.");
                return unavailable(packFormat);
            }
            Map<String, Character> glyphs = assignGlyphs(icons.keySet());
            byte[] atlas = composeAtlas(icons);
            writePackFiles(packDirectory, packFormat, glyphs, atlas);
            byte[] zipBytes = zipDirectory(packDirectory);
            if (!Arrays.equals(zipBytes, readIfExists(zip))) {
                Files.createDirectories(zip.getParent());
                Files.write(zip, zipBytes);
            }
            String sha1 = sha1(zipBytes);
            logger.info("Icon resource pack ready: " + icons.size() + " icons, format " + packFormat
                    + ", " + zipBytes.length + " bytes, sha1 " + sha1 + " (" + zip.getFileName() + ")");
            return new IconPack(glyphs, zip, sha1, packFormat);
        } catch (IOException | RuntimeException | NoClassDefFoundError e) {
            // NoClassDefFoundError covers a JVM without the java.desktop module.
            logger.warning("Could not build the chat icon resource pack (" + e + "); chat icons stay hidden.");
            return unavailable(packFormat);
        }
    }

    boolean isAvailable() {
        return !glyphs.isEmpty();
    }

    int iconCount() {
        return glyphs.size();
    }

    /** The generated zip, or {@code null} when the pack could not be built. */
    Path zip() {
        return zip;
    }

    String sha1() {
        return sha1;
    }

    int packFormat() {
        return packFormat;
    }

    /** Icon names in glyph order, useful for logs and commands. */
    Collection<String> iconNames() {
        return List.copyOf(glyphs.keySet());
    }

    /**
     * The glyph for a rank: the icon overrides win, otherwise the rank's own name decides
     * (Senior Tech Support looks for {@code senior_tech_support.png}).
     *
     * @return the one-character glyph, or empty when this server has no icon for the rank
     */
    Optional<String> glyphFor(String rankName, Map<String, String> overrides) {
        if (rankName == null || glyphs.isEmpty()) return Optional.empty();
        String icon = overrides.getOrDefault(rankName.trim().toLowerCase(Locale.ROOT), defaultIconName(rankName));
        Character glyph = glyphs.get(icon.toLowerCase(Locale.ROOT));
        return glyph == null ? Optional.empty() : Optional.of(String.valueOf(glyph));
    }

    /** "Senior Tech Support" becomes {@code senior_tech_support}, matching the shipped files. */
    static String defaultIconName(String rankName) {
        return rankName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static void extractBundledIcons(Logger logger, Path iconsDirectory) throws IOException {
        Files.createDirectories(iconsDirectory);
        for (String icon : BUNDLED_ICONS) {
            Path target = iconsDirectory.resolve(icon + ".png");
            // An icon the admin put there themselves is never overwritten.
            if (Files.exists(target)) continue;
            try (InputStream bundled = IconPack.class.getClassLoader()
                    .getResourceAsStream("icons/" + icon + ".png")) {
                if (bundled == null) {
                    logger.warning("Bundled icon is missing from the jar: " + icon + ".png");
                    continue;
                }
                Files.copy(bundled, target);
            }
        }
    }

    private static Map<String, BufferedImage> readIcons(Logger logger, Path iconsDirectory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(iconsDirectory, "*.png")) {
            for (Path file : stream) files.add(file);
        }
        // Sorted by name so the glyph a rank gets never changes while the icon set is stable.
        files.sort(Comparator.comparing(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)));

        Map<String, BufferedImage> icons = new LinkedHashMap<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String iconName = fileName.substring(0, fileName.length() - ".png".length()).toLowerCase(Locale.ROOT);
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                logger.warning("Skipping unreadable icon " + fileName + ".");
                continue;
            }
            icons.put(iconName, image);
        }
        return icons;
    }

    private static Map<String, Character> assignGlyphs(Collection<String> iconNames) {
        Map<String, Character> glyphs = new LinkedHashMap<>();
        int index = 0;
        for (String name : iconNames) {
            glyphs.put(name, (char) (FIRST_GLYPH + index++));
        }
        return glyphs;
    }

    /** Draws every icon into its own cell of a single-row atlas, keeping the aspect ratio. */
    private static byte[] composeAtlas(Map<String, BufferedImage> icons) throws IOException {
        BufferedImage atlas = new BufferedImage(CELL_SIZE * icons.size(), CELL_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cell = 0;
            for (BufferedImage icon : icons.values()) {
                double scale = Math.min((double) CELL_SIZE / icon.getWidth(), (double) CELL_SIZE / icon.getHeight());
                int width = Math.max(1, (int) Math.round(icon.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(icon.getHeight() * scale));
                int x = cell * CELL_SIZE + (CELL_SIZE - width) / 2;
                int y = (CELL_SIZE - height) / 2;
                graphics.drawImage(icon, x, y, width, height, null);
                cell++;
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(atlas, "png", buffer);
        return buffer.toByteArray();
    }

    private static void writePackFiles(Path packDirectory, int packFormat,
                                       Map<String, Character> glyphs, byte[] atlas) throws IOException {
        write(packDirectory.resolve("pack.mcmeta"), packMeta(packFormat, glyphs.size()));
        write(packDirectory.resolve(FONT_FILE), fontJson(glyphs.values()));
        write(packDirectory.resolve(ATLAS_FILE), atlas);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void write(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    static String packMeta(int packFormat, int iconCount) {
        return """
                {
                  "pack": {
                    "pack_format": %d,
                    "description": "SCSO chat icons (%d icons)"
                  }
                }
                """.formatted(packFormat, iconCount);
    }

    /** One bitmap provider whose cells, left to right, are the icons in glyph order. */
    static String fontJson(Collection<Character> glyphs) {
        StringBuilder chars = new StringBuilder(glyphs.size() * 6);
        for (Character glyph : glyphs) {
            chars.append(String.format("\\u%04X", (int) glyph));
        }
        return """
                {
                  "providers": [
                    {
                      "type": "bitmap",
                      "file": "%s:font/icons.png",
                      "ascent": %d,
                      "height": %d,
                      "chars": [
                        "%s"
                      ]
                    }
                  ]
                }
                """.formatted(NAMESPACE, GLYPH_ASCENT, GLYPH_HEIGHT, chars);
    }

    private static byte[] zipDirectory(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(file -> entryName(directory, file)));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(entryName(directory, file));
                // A fixed timestamp keeps the zip - and therefore its hash - identical
                // across restarts, so clients do not re-download an unchanged pack.
                entry.setTime(FIXED_ZIP_TIME);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static String entryName(Path directory, Path file) {
        return directory.relativize(file).toString().replace('\\', '/');
    }

    private static byte[] readIfExists(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String sha1(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            StringBuilder hex = new StringBuilder(40);
            for (byte value : digest.digest(content)) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by every Java runtime", e);
        }
    }
}
