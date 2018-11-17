package com.dg.sites;

import com.dg.Database;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.servlet.MultipartConfigElement;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static spark.Spark.*;

/**
 * @author doc
 */
public class DgPic {
    private static Logger log = LoggerFactory.getLogger(DgPic.class);

    private final ArrayList<String> allPossibleNames;
    private final Database database;

    private final Pattern imageNamePattern = Pattern.compile("(?<name>[b-df-hj-np-tv-z][aeiouy][b-df-hj-np-tv-z][aeiouy][b-df-hj-np-tv-z])(?<mini>\\.mini)?");

    private DgPic(final int port) {
        log.info("Starting up");

        allPossibleNames = generateAllPossibleFileNames();
        Collections.shuffle(allPossibleNames);

        log.info("Generated all names, connecting to db...");
        database = new Database("database/db");
        database.connect();
        log.info("Connection established");

        start(port);

        awaitInitialization();

        log.info("Done!");
    }

    public static void ignite(final int port) {
        new DgPic(port);
    }

    private void start(final int port) {
        port(port);
        threadPool(8);

        before((request, response) -> {
            if (request.raw().getAttribute("org.eclipse.jetty.multipartConfig") == null) {
                MultipartConfigElement config = new MultipartConfigElement(System.getProperty("java.io.tmpdir"));
                request.raw().setAttribute("org.eclipse.jetty.multipartConfig", config);
            }
        });

        post("/upload", (req, res) -> {
            final String versionParam = req.queryParams("version");
            final int version = versionParam == null ? 0 : Integer.valueOf(versionParam);

            switch (version) {
                case 0: {
                    try (final InputStream in = req.raw().getInputStream()) {
                        return uploadImage(in, req.ip());
                    }
                }

                case 1: {
                    try (final InputStream in = req.raw().getPart("image").getInputStream()) {
                        final String name = uploadImage(in, req.ip());
                        return String.format("{ \"success\": true, \"answer\": { \"url\": \"%s\" } }", name);
                    }
                }

                default: {
                    throw new IllegalArgumentException("Incorrect version: " + version);
                }
            }
        });

        get("/:fileName", (req, res) -> {
            final String fileUrl = req.params(":fileName");
            final Matcher matcher = imageNamePattern.matcher(fileUrl);

            if (!matcher.matches()) {
                halt(404);
            }

            final String fileName = matcher.group("name") + ".jpg";
            final File imageFile;

            if (matcher.group("mini") == null) {
                imageFile = imageFileOrNone(Paths.get("scr", "mini", fileName).toFile());
            } else {
                imageFile = imageFileOrNone(Paths.get("scr", fileName).toFile());
            }

            res.header("Content-Type", "image/jpeg");
            res.header("Content-Length", String.valueOf(imageFile.length()));

            try (OutputStream out = res.raw().getOutputStream()) {
                FileUtils.copyFile(imageFile, out);
            }

            return "";
        });

        exception(Exception.class, (exception, req, res) -> {
            log.error("Error in {}", req.pathInfo(), exception);

            res.status(500);
            res.body(String.format("{ \"success\": false, \"message\": \"%s\" }", exception.getMessage()));
        });
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

    private ArrayList<String> generateAllPossibleFileNames() {
        final ImmutableSet<Character> vowels = ImmutableSet.of('a', 'e', 'y', 'u', 'i', 'o');
        final ImmutableSet<Character> consonants = ImmutableSet.of(
                'q', 'w', 'r', 't', 'p', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'z', 'x', 'c', 'v', 'b', 'n', 'm');

        final ImmutableList<ImmutableSet<Character>> nameGeneratorBase = ImmutableList.of(
                consonants, vowels, consonants, vowels, consonants
        );

        final ImmutableSet<List<Character>> product = ImmutableSet.copyOf(Sets.cartesianProduct(nameGeneratorBase));

        return product.stream().map(characters -> {
            final char[] result = new char[characters.size()];

            for (int index = 0; index < characters.size(); index++) {
                result[index] = characters.get(index);
            }

            return new String(result);
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    private synchronized ImmutableList<String> generateNextFileNames(int amount) {
        final ImmutableList.Builder<String> builder = ImmutableList.builder();

        while (amount > 0) {
            builder.add(allPossibleNames.remove(allPossibleNames.size() - 1));

            amount--;
        }

        return builder.build();
    }

    private PreparedStatement query(final String query) {
        try {
            return database.getConnection().prepareStatement(query);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String getNextFileName() {
        try (final ResultSet result = query("SELECT NAME FROM SCREENS WHERE FREE = TRUE ORDER BY RAND() LIMIT 1").executeQuery()) {
            if (result.next()) {
                return result.getString("NAME");
            }

            log.info("No more names in the database, generating 100 more");

            final ImmutableList<String> moreNames = generateNextFileNames(100);
            final String mergeName =
                    "merge into screens using(values(?)) \n" +
                            "as vars(name)\n" +
                            "on screens.name = vars.name\n" +
                            "when not matched then\n" +
                            "insert values vars.name, NOW(), 0, NULL, TRUE";

            for (final String name : moreNames) {
                try (final PreparedStatement mergeNameQuery = query(mergeName)) {
                    mergeNameQuery.setString(1, name);
                    mergeNameQuery.execute();
                }
            }

            return getNextFileName();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String saveNewImage(final String fromIp, final BufferedImage image, final byte[] imageData) {
        try {
            log.info("Trying to upload a new image of resolution {}x{}", image.getWidth(), image.getHeight());

            final String name = getNextFileName();

            final BufferedImage thumbnail = generateThumbnail(image, name, 180, 140);
            ImageIO.write(thumbnail, "png", new File(new File("scr", "mini"), name + ".jpg"));

            FileUtils.writeByteArrayToFile(new File("scr", name + ".jpg"), imageData);

            try (final PreparedStatement query = query("UPDATE SCREENS SET UPLOADED = NOW(), FREE = FALSE, UPLOAD_IP = ? WHERE NAME = ?")) {
                query.setString(1, fromIp);
                query.setString(2, name);
                query.execute();
            }

            // TODO tryToUpdateGallery

            log.info("Uploaded a new image of size {}", FileUtils.byteCountToDisplaySize(imageData.length));

            return name;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String uploadImage(final InputStream inputStream, final String ip) throws Exception {
        final BufferedImage inputImage = ImageIO.read(inputStream);

        final ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        ImageIO.write(inputImage, "png", pngOut);

        final int twoMegabytes = 2 * 1024 * 1024;

        if (pngOut.size() <= twoMegabytes) {
            return saveNewImage(ip, inputImage, pngOut.toByteArray());
        }

        final ByteArrayOutputStream jpgOut = new ByteArrayOutputStream();
        ImageIO.write(inputImage, "jpg", jpgOut);

        if (jpgOut.size() <= twoMegabytes) {
            return saveNewImage(ip, inputImage, jpgOut.toByteArray());
        }

        throw new IllegalArgumentException("Image is too large to be saved");
    }
}
