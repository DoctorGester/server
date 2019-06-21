package com.dg.sites;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.freeutils.httpserver.HTTPServer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.freeutils.httpserver.HTTPServer.MultipartIterator.Part;

/**
 * @author doc
 */
public class DgPic {
    private static Logger log = LoggerFactory.getLogger(DgPic.class);

    private final int[] allPossibleNamesEncoded;
    private int nameCursor = 0;

    private final DataSource database;

    private final Pattern imagePathPattern = Pattern.compile("/(?<name>[b-df-hj-np-tv-z][aeiouy][b-df-hj-np-tv-z][aeiouy][b-df-hj-np-tv-z])(?<mini>\\.mini)?");

    private final Map<String, StaticFile> staticFiles;

    private static class StaticFile {
        private String contentType;
        private byte[] cachedContents;

        private StaticFile(String contentType, byte[] cachedContents) {
            this.contentType = contentType;
            this.cachedContents = cachedContents;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getCachedContents() {
            return cachedContents;
        }
    }

    private DgPic(final HTTPServer.VirtualHost host) {
        log.info("Igniting");

        allPossibleNamesEncoded = generateAllPossibleFileNames();
        System.gc();

        log.info("Generated all {} names, connecting to db...", allPossibleNamesEncoded.length);

        try {
            final HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
            hikariConfig.setJdbcUrl("jdbc:hsqldb:database/db");
            hikariConfig.setUsername("sa");
            hikariConfig.setPassword("");
            hikariConfig.setMaximumPoolSize(4);

            database = new HikariDataSource(hikariConfig);

            try (final Connection connection = database.getConnection()) {
                for (final String table: new String[] { "VIEWS", "USERS", "GALLERY_MEMBERS", "TOKENS", "DOWNLOADS", "CAPTCHAS", "VIEWS_BY_REFERER" }) {
                    setTableCached(connection, table);
                }
            }
        } catch (final Exception e) {
            log.error("Error while connecting to the database", e);
            throw new RuntimeException(e);
        }

        log.info("Connection established");

        log.info("Enumerating static files");

        staticFiles = new HashMap<>();

        try {
            Files.walk(Paths.get("static"))
                    .filter(Files::isRegularFile)
                    .forEach(path -> staticFiles.put(path.toString(), createStaticFile(path)));
        } catch (final Exception e) {
            log.error("Unable to enumerate static files", e);
        }

        log.info("Total static files: {}", staticFiles.size());

        start(host);

        log.info("Done!");
    }

    public static void ignite(final HTTPServer.VirtualHost host) {
        new DgPic(host);
    }

    private static void setTableCached(final Connection connection, final String table) throws SQLException {
        try (final CallableStatement call = connection.prepareCall("SET TABLE " + table + " TYPE CACHED")) {
            log.info("Set table cached {}: {}", table, call.execute());
        }
    }

    private static StaticFile createStaticFile(final Path path) {
        try {
            final long size = Files.size(path);
            final long maxSize = 1024 * 128; // 128kb
            final byte[] contents;

            if (size <= maxSize) {
                contents = Files.readAllBytes(path);

                log.info("Cached static file {} with size {}b", path, contents.length);
            } else {
                contents = null;

                log.info("Serving {} from disk", path);
            }

            return new StaticFile(filePathToContentType(path.toString()), contents);
        } catch (IOException e) {
            throw new RuntimeException("Error when creating static file", e);
        }
    }

    private static String filePathToContentType(final String filePath) {
        final int index = filePath.lastIndexOf('.');

        if (index == -1) {
            return "application/octet-stream";
        }

        final String extenstion = filePath.substring(index + 1);

        switch (extenstion) {
            case "html": return "text/html";
            case "js": return "text/javascript";
            case "css": return "text/css";
            case "png": return "image/png";
            case "jpg": return "image/jpeg";
            case "jpeg": return "image/jpeg";

            default: return "application/octet-stream";
        }
    }

    private HTTPServer.ContextHandler wrapHandler(final HTTPServer.ContextHandler handler) {
        return (req, res) -> {
            try {
                /*String accept = req.getHeaders().get("Accept-Encoding");

                if (accept != null && accept.contains("gzip")) {
                    res.getHeaders().add("Content-Encoding", "gzip");
                }*/

                return handler.serve(req, res);
            } catch (final Exception e) {
                log.error("Error when handling request {}", req.getURI(), e);

                return 500;
            }
        };
    }

    private void post(final HTTPServer.VirtualHost host, final String path, final HTTPServer.ContextHandler handler) {
        host.addContext(path, wrapHandler(handler), "POST");
    }

    private void get(final HTTPServer.VirtualHost host, final String path, final HTTPServer.ContextHandler handler) {
        host.addContext(path, wrapHandler(handler), "GET");
    }

    private Iterable<Part> multipartParts(final HTTPServer.Request request) {
        try {
            final HTTPServer.MultipartIterator iterator = new HTTPServer.MultipartIterator(request);

            return () -> iterator;
        } catch (final IOException e) {
            log.error("Error while reading multipart data", e);
            throw new RuntimeException(e);
        }
    }

    private void start(final HTTPServer.VirtualHost host) {
        post(host, "/upload", (req, res) -> {
            final int version = Integer.valueOf(req.getParams().getOrDefault("version", "1"));

            switch (version) {
                case 0: {
                    res.send(200, uploadImage(req.getBody()));

                    break;
                }

                case 1: {
                    for (Part part: multipartParts(req)) {
                        if ("image".equals(part.getName())) {
                            final String name = uploadImage(part.getBody());
                            res.send(200, String.format("{ \"success\": true, \"answer\": { \"url\": \"%s\" } }", name));

                            return 0;
                        }
                    }

                    return 400;
                }

                default: {
                    throw new IllegalArgumentException("Incorrect version: " + version);
                }
            }

            return 0;
        });

        get(host, "/static", (req, res) -> {
            final String path = req.getPath();
            final String relativePath = path.substring(1);
            final StaticFile staticFile = staticFiles.get(relativePath);

            if (staticFile == null) {
                return 404;
            }

            final byte[] contents = staticFile.getCachedContents();

            if (contents != null) {
                setupOkResponse(res, staticFile.getContentType(), contents.length);

                try {
                    IOUtils.copyLarge(new ByteArrayInputStream(contents), res.getBody());
                } catch (final SocketException exception) {
                    log.error("Socket exception when streaming file: {}", exception.getMessage());
                }

                return 0;
            }

            return serveFile(res, new File(relativePath), staticFile.getContentType());
        });

        get(host, "/", (req, res) -> {
            final String path = req.getPath();

            if ("/".equals(path) || "/index.html".equals(path)) {
                return serveFile(res, new File("static/index.html"), "text/html");
            }

            final Matcher matcher = imagePathPattern.matcher(path);

            if (!matcher.matches()) {
                log.info("Tried to access unmapped {}", req.getPath());

                return 404;
            }

            final String fileName = matcher.group("name") + ".jpg";
            final File imageFile;

            if (matcher.group("mini") != null) {
                imageFile = imageFileOrNone(Paths.get("scr", "mini", fileName).toFile());
            } else {
                imageFile = imageFileOrNone(Paths.get("scr", fileName).toFile());
            }

            return serveFile(res, imageFile, "image/jpeg");
        });
    }

    private void setupOkResponse(final HTTPServer.Response res, final String contentType, final long contentLength) throws IOException {
        res.getHeaders().add("Content-Type", contentType);
        res.getHeaders().add("Content-Length", String.valueOf(contentLength));

        res.sendHeaders(200);
    }

    private int serveFile(final HTTPServer.Response res, final File file, final String contentType) throws IOException {
        if (!file.exists()) {
            return 404;
        }

        setupOkResponse(res, contentType, file.length());

        try {
            FileUtils.copyFile(file, res.getBody());
        } catch (final SocketException exception) {
            log.error("Socket exception when streaming file: {}", exception.getMessage());
        }

        return 0;
    }

    private File imageFileOrNone(final File file) {
        if (!file.exists()) {
            return new File("scr", "none.jpg");
        }

        return file;
    }

    private BufferedImage resizeImage(final BufferedImage image, final int targetWidth, final int targetHeight) {
        int w = image.getWidth();
        int h = image.getHeight();

        BufferedImage result;

        while (true) {
            if (w > targetWidth) {
                w = w / 2;

                if (w < targetWidth) {
                    w = targetWidth;
                }
            }

            if (h > targetHeight) {
                h = h / 2;

                if (h < targetHeight) {
                    h = targetHeight;
                }
            }

            result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

            final Graphics2D graphics = result.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, w, h, null);
            graphics.dispose();

            if (w == targetWidth) {
                break;
            }
        }

        return result;
    }

    private BufferedImage generateThumbnail(final BufferedImage fromImage, final String name, final int maxWidth, final int maxHeight) {
        final BufferedImage thumbnail = new BufferedImage(maxWidth, maxHeight, BufferedImage.TYPE_INT_ARGB);

        final Graphics2D graphics = thumbnail.createGraphics();
        final int originalWidth = fromImage.getWidth();
        final int originalHeight = fromImage.getHeight();

        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, maxWidth, maxHeight);

        final int thumbWidth = maxWidth;
        final int thumbHeight = maxHeight - 16;

        if (originalWidth <= thumbWidth && originalHeight <= thumbHeight) {
            graphics.drawImage(fromImage, (thumbWidth - originalWidth) / 2, (thumbHeight - originalHeight) / 2, null);
        } else {
            float scale = Math.min(thumbWidth / (float) originalWidth, thumbHeight / (float) originalHeight);

            float newWidth = originalWidth;
            float newHeight = originalHeight;

            if (scale < 1.0) {
                newWidth = scale * originalWidth;
                newHeight = scale * originalHeight;
            }

            float targetX = (thumbWidth - newWidth) / 2;
            float targetY = (thumbHeight - newHeight) / 2;

            final BufferedImage scaled = resizeImage(fromImage, (int) newWidth, (int) newHeight);
            graphics.drawImage(scaled, (int) targetX, (int) targetY, null);
        }

        final Font font = new Font("arial", Font.PLAIN, 10);

        graphics.setFont(font);
        graphics.setColor(new Color(40, 40, 40));
        graphics.fillRect(0, thumbHeight, thumbWidth, 16);

        graphics.setColor(Color.WHITE);
        graphics.drawString("dg-pic.tk/" + name, 4, maxHeight - 4);

        final String sizeLabel = "(" + originalWidth + "x" + originalHeight + ")";
        final int labelWidth = graphics.getFontMetrics().stringWidth(sizeLabel);

        graphics.drawString(sizeLabel, maxWidth - labelWidth - 4, maxHeight);
        graphics.dispose();

        return thumbnail;
    }

    private static void shuffleArray(int[] array) {
        int index, temp;
        final Random random = new Random();
        for (int i = array.length - 1; i > 0; i--) {
            index = random.nextInt(i + 1);
            temp = array[index];
            array[index] = array[i];
            array[i] = temp;
        }
    }

    private int[] generateAllPossibleFileNames() {
        final char[] vowels = {'a', 'e', 'y', 'u', 'i', 'o'};
        final char[] consonants = {'q', 'w', 'r', 't', 'p', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'z', 'x', 'c', 'v', 'b', 'n', 'm'};

        int index = 0;

        final int productSize = consonants.length * vowels.length * consonants.length * vowels.length * consonants.length;
        final int[] product = new int[productSize];

        for (final char c1 : consonants) {
            for (final char c2 : vowels) {
                for (final char c3 : consonants) {
                    for (final char c4 : vowels) {
                        for (final char c5 : consonants) {
                            // 6 bits per char
                            final int encoded =
                                    (c1 - 'a') << 26 |
                                    (c2 - 'a') << 20 |
                                    (c3 - 'a') << 14 |
                                    (c4 - 'a') << 8 |
                                    (c5 - 'a') << 2;

                            product[index++] = encoded;
                        }
                    }
                }
            }
        }

        shuffleArray(product);

        return product;
    }

    private static String decodeWord(final int encoded) {
        // Only using 6 bits
        final int mask = 0b00000000000000000000000000111111;
        final char c1 = (char) (((encoded >> 26) & mask) + 'a');
        final char c2 = (char) (((encoded >> 20) & mask) + 'a');
        final char c3 = (char) (((encoded >> 14) & mask) + 'a');
        final char c4 = (char) (((encoded >> 8)  & mask) + 'a');
        final char c5 = (char) (((encoded >> 2)  & mask) + 'a');

        return new String(new char[] { c1, c2, c3, c4, c5 });
    }

    private synchronized String[] generateNextFileNames(int amount) {
        final String[] result = new String[amount];

        int index = 0;

        while (amount > 0) {
            result[index++] = decodeWord(allPossibleNamesEncoded[nameCursor++]);

            amount--;
        }

        return result;
    }

    private PreparedStatement query(final Connection connection, final String query) {
        try {
            return connection.prepareStatement(query);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String getNextFileName() {
        try (final Connection connection = database.getConnection();
             final ResultSet result = query(connection, "SELECT NAME FROM SCREENS WHERE FREE = TRUE ORDER BY RAND() LIMIT 1").executeQuery()) {
            if (result.next()) {
                return result.getString("NAME");
            }

            log.info("No more names in the database, generating 100 more");

            final String[] moreNames = generateNextFileNames(100);
            final String mergeName =
                    "merge into screens using(values(?)) \n" +
                            "as vars(name)\n" +
                            "on screens.name = vars.name\n" +
                            "when not matched then\n" +
                            "insert values vars.name, NOW(), 0, NULL, TRUE";

            for (final String name : moreNames) {
                try (final PreparedStatement mergeNameQuery = query(connection, mergeName)) {
                    mergeNameQuery.setString(1, name);
                    mergeNameQuery.execute();
                }
            }

            return getNextFileName();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String saveNewImage(final BufferedImage image, final byte[] imageData) {
        try {
            log.info("Trying to upload a new image of resolution {}x{}", image.getWidth(), image.getHeight());

            final String name = getNextFileName();

            final BufferedImage thumbnail = generateThumbnail(image, name, 180, 140);
            ImageIO.write(thumbnail, "png", new File(new File("scr", "mini"), name + ".jpg"));

            FileUtils.writeByteArrayToFile(new File("scr", name + ".jpg"), imageData);

            try (final Connection connection = database.getConnection();
                 final PreparedStatement query = query(connection, "UPDATE SCREENS SET UPLOADED = NOW(), FREE = FALSE WHERE NAME = ?")) {
                query.setString(1, name);
                query.execute();
            }

            // TODO tryToUpdateGallery

            log.info("Uploaded a new image of size {}", FileUtils.byteCountToDisplaySize(imageData.length));

            return name;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String uploadImage(final InputStream inputStream) throws IOException {
        final BufferedImage inputImage = ImageIO.read(inputStream);

        final ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        ImageIO.write(inputImage, "png", pngOut);

        final int twoMegabytes = 2 * 1024 * 1024;

        if (pngOut.size() <= twoMegabytes) {
            return saveNewImage(inputImage, pngOut.toByteArray());
        }

        final ByteArrayOutputStream jpgOut = new ByteArrayOutputStream();
        ImageIO.write(inputImage, "jpg", jpgOut);

        if (jpgOut.size() <= twoMegabytes) {
            return saveNewImage(inputImage, jpgOut.toByteArray());
        }

        throw new IllegalArgumentException("Image is too large to be saved");
    }
}
