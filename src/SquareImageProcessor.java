import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

public class SquareImageProcessor {

    private static final int WHITE_THRESHOLD = 245;
    private static final int PADDING = 20;
    private static final int IMAGE_SIZE = 1000;

    public static void main(String[] args) {
        String inputPath = "res/input";
        String outputPath = "res/output";
        String logosOutputPath = outputPath + "/logos";
        String productsOutputPath = outputPath + "/product-images";

        File inputFolder = new File(inputPath);
        File logosOutputFolder = new File(logosOutputPath);
        File productsOutputFolder = new File(productsOutputPath);

        if (!inputFolder.exists() || !inputFolder.isDirectory()) {
            System.out.println("Input folder is invalid: " + inputPath);
            return;
        }

        logosOutputFolder.mkdirs();
        productsOutputFolder.mkdirs();

        File[] files = inputFolder.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".bmp");
        });

        if (files == null || files.length == 0) {
            System.out.println("No image files found in input folder.");
            return;
        }

        for (File file : files) {
            try {
                BufferedImage original = ImageIO.read(file);

                if (original == null) {
                    System.out.println("Could not read image: " + file.getName());
                    continue;
                }

                String originalName = file.getName();
                String lowerName = originalName.toLowerCase();
                boolean isLogo = lowerName.contains("logo");

                BufferedImage cropped = cropWhiteMargins(original);

                int width = cropped.getWidth();
                int height = cropped.getHeight();
                int squareSize = Math.max(width, height);

                BufferedImage squareImage = new BufferedImage(squareSize, squareSize, BufferedImage.TYPE_INT_RGB);

                Graphics2D gSquare = squareImage.createGraphics();
                applyQualityHints(gSquare);

                gSquare.setColor(Color.WHITE);
                gSquare.fillRect(0, 0, squareSize, squareSize);

                int x = (squareSize - width) / 2;
                int y = (squareSize - height) / 2;

                gSquare.drawImage(cropped, x, y, null);
                gSquare.dispose();

                BufferedImage finalImage;

                if (isLogo) {

                    // Sets the logo to 1:1 without scaling, as logos often look better when not resized
                    finalImage = squareImage;

                } else {

                    finalImage = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);

                    Graphics2D gScaled = finalImage.createGraphics();
                    applyQualityHints(gScaled);

                    gScaled.drawImage(squareImage, 0, 0, IMAGE_SIZE, IMAGE_SIZE, null);
                    gScaled.dispose();
                }

                int dotIndex = originalName.lastIndexOf('.');
                String baseName = dotIndex > 0 ? originalName.substring(0, dotIndex) : originalName;

                String newName = baseName + "_1.jpg";
                File targetFolder = isLogo ? logosOutputFolder : productsOutputFolder;
                File outputFile = new File(targetFolder, newName);

                saveAsJpeg(finalImage, outputFile);

                System.out.println("Processed: " + file.getName());

            } catch (IOException e) {
                System.out.println("Error processing " + file.getName() + ": " + e.getMessage());
            }
        }
        System.out.println("All done!");
    }

    private static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE);
    }

    private static void saveAsJpeg(BufferedImage image, File outputFile) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");

        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer found");
        }

        ImageWriter writer = writers.next();

        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(1.0f);

        try (FileImageOutputStream output = new FileImageOutputStream(outputFile)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage cropWhiteMargins(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        int top = 0;
        int bottom = height - 1;
        int left = 0;
        int right = width - 1;

        while (top < height && isRowWhite(image, top)) {
            top++;
        }

        while (bottom >= top && isRowWhite(image, bottom)) {
            bottom--;
        }

        while (left < width && isColumnWhite(image, left, top, bottom)) {
            left++;
        }

        while (right >= left && isColumnWhite(image, right, top, bottom)) {
            right--;
        }

        if (left > right || top > bottom) {
            return image;
        }

        left = Math.max(0, left - PADDING);
        top = Math.max(0, top - PADDING);
        right = Math.min(width - 1, right + PADDING);
        bottom = Math.min(height - 1, bottom + PADDING);

        return image.getSubimage(left, top, right - left + 1, bottom - top + 1);
    }

    private static boolean isRowWhite(BufferedImage image, int y) {
        for (int x = 0; x < image.getWidth(); x++) {
            if (!isWhite(image.getRGB(x, y))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isColumnWhite(BufferedImage image, int x, int top, int bottom) {
        for (int y = top; y <= bottom; y++) {
            if (!isWhite(image.getRGB(x, y))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWhite(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        return r >= WHITE_THRESHOLD && g >= WHITE_THRESHOLD && b >= WHITE_THRESHOLD;
    }
}