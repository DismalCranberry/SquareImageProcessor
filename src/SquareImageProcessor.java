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

        File inputFolder = new File(inputPath);
        File outputFolder = new File(outputPath);

        if (!inputFolder.exists() || !inputFolder.isDirectory()) {
            System.out.println("Input folder is invalid: " + inputPath);
            return;
        }

        if (!outputFolder.exists()) {
            outputFolder.mkdirs();
        }

        File[] files = inputFolder.listFiles((_, name) -> {
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
                BufferedImage cropped = cropWhiteMargins(original);

                int width = cropped.getWidth();
                int height = cropped.getHeight();
                int size = Math.max(width, height);

                BufferedImage squareImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = squareImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, size, size);

                int x = (size - width) / 2;
                int y = (size - height) / 2;
                g2d.drawImage(cropped, x, y, null);
                g2d.dispose();

                int newSize = IMAGE_SIZE;
                BufferedImage finalImage = new BufferedImage(newSize, newSize, BufferedImage.TYPE_INT_RGB);
                Graphics2D gScaled = finalImage.createGraphics();
                gScaled.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                gScaled.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                gScaled.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                gScaled.drawImage(squareImage, 0, 0, newSize, newSize, null);
                gScaled.dispose();

                String lower = file.getName().toLowerCase();
                String format = lower.endsWith(".png") ? "png" : "jpg";
                File outputFile = new File(outputFolder, file.getName());

                if (format.equals("jpg")) {
                    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
                    ImageWriter writer = writers.next();
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(1.0f);

                    try (FileImageOutputStream output = new FileImageOutputStream(outputFile)) {
                        writer.setOutput(output);
                        writer.write(null, new IIOImage(finalImage, null, null), param);
                    }
                    writer.dispose();
                } else {
                    ImageIO.write(finalImage, format, outputFile);
                }
                System.out.println("Processed: " + file.getName());
            } catch (IOException e) {
                System.out.println("Error processing " + file.getName() + ": " + e.getMessage());
            }
        }
        System.out.println("✅ All done!");
    }

    private static BufferedImage cropWhiteMargins(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        int top = 0;
        int bottom = height - 1;
        int left = 0;
        int right = width - 1;

        // Find top
        while (top < height && isRowWhite(image, top)) {
            top++;
        }

        // Find bottom
        while (bottom >= top && isRowWhite(image, bottom)) {
            bottom--;
        }

        // Find left
        while (left < width && isColumnWhite(image, left, top, bottom)) {
            left++;
        }

        // Find right
        while (right >= left && isColumnWhite(image, right, top, bottom)) {
            right--;
        }

        // If image is fully white, return original
        if (left > right || top > bottom) {
            return image;
        }

        // Add padding back
        left = Math.max(0, left - SquareImageProcessor.PADDING);
        top = Math.max(0, top - SquareImageProcessor.PADDING);
        right = Math.min(width - 1, right + SquareImageProcessor.PADDING);
        bottom = Math.min(height - 1, bottom + SquareImageProcessor.PADDING);

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