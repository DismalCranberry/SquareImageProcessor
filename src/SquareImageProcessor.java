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

        File[] files = inputFolder.listFiles((_, name) -> name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".png"));

        if (files == null || files.length == 0) {
            System.out.println("No image files found in input folder.");
            return;
        }

        for (File file : files) {
            try {
                BufferedImage original = ImageIO.read(file);
                if (original == null) {
                    System.out.println("Skipping non-image file: " + file.getName());
                    continue;
                }

                int width = original.getWidth();
                int height = original.getHeight();
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
                g2d.drawImage(original, x, y, null);
                g2d.dispose();

                BufferedImage finalImage = squareImage;
                if (size > 1000) {
                    int newSize = 1000;
                    BufferedImage scaled = new BufferedImage(newSize, newSize, BufferedImage.TYPE_INT_RGB);
                    Graphics2D gScaled = scaled.createGraphics();
                    gScaled.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    gScaled.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    gScaled.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    gScaled.drawImage(squareImage, 0, 0, newSize, newSize, null);
                    gScaled.dispose();
                    finalImage = scaled;
                }

                String format = file.getName().toLowerCase().endsWith(".png") ? "png" : "jpg";
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
}
