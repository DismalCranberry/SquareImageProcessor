# Square Image Processor

A Java tool for preparing e-commerce product images. It detects and crops white or transparent margins, centers the
subject on a square white canvas, and exports high-quality JPEGs. It also supports **static WebP images** using a custom
Java lossless decoder and an FFmpeg fallback for lossy WebP.

## Features

- Accepts **JPG, JPEG, PNG, BMP, and WebP** input files.
- Crops white, near-white, and fully transparent margins using a configurable threshold.
- Centers the subject on a **1:1 white square canvas**.
- Adds configurable padding within the bounds of the original image.
- Resizes **product images to 1000 × 1000 pixels**.
- Preserves the cropped square dimensions for **logos** (no resizing).
- Separates logos and product images into different output folders.
- Converts all supported inputs to **JPEG** at the highest configured JPEG quality setting.
- Keeps the original base filename and adds `_1.jpg`.

## Requirements

- **Java JDK 8 or newer**, with `javac` and `java` available.
- **FFmpeg**, only if you need to process **lossy VP8 WebP** images. Lossless VP8L WebP files are decoded directly in
  Java.
- No additional Java libraries or Maven/Gradle dependencies.

### Install FFmpeg on Windows (for lossy WebP)

You can install it through Windows Package Manager (WinGet):

```powershell
winget show --id Gyan.FFmpeg --exact --source winget
winget install --id Gyan.FFmpeg --exact --source winget
```

Open a **new terminal** after installation and verify:

```powershell
ffmpeg -version
```

If Java is launched from IntelliJ IDEA and it cannot find FFmpeg, restart the IDE so it picks up the updated `PATH`.

If `ffmpeg` is not on your `PATH`, set its absolute executable path in `WebPReader.java`:

```java
private static final String FFMPEG_COMMAND = "C:\\ffmpeg\\bin\\ffmpeg.exe";
```

Replace this example with the actual location of `ffmpeg.exe` on your computer.

## Folder Structure

```text
project/
├── SquareImageProcessor.java
├── WebPReader.java
└── res/
    ├── input/                   # Put original images here
    └── output/                  # Created automatically when processing
        ├── logos/              # Filenames containing "logo"
        └── product-images/     # All other input images
```

The input directory (`res/input`) must exist before running the program. Paths are relative to the program's **working
directory**.

## Usage

1. Create `res/input` in your project working directory if it does not exist.
2. Place your source images directly in `res/input`.
3. Compile the Java files:

   ```powershell
   javac WebPReader.java SquareImageProcessor.java
   ```

4. Run the program:

   ```powershell
   java SquareImageProcessor
   ```

5. Find the JPEG files in `res/output/product-images` and `res/output/logos`.

For example:

```text
res/input/221508_3.webp       -> res/output/product-images/221508_3_1.jpg
res/input/brand_logo.png      -> res/output/logos/brand_logo_1.jpg
```

A file is classified as a logo when its **filename contains `logo`**, case-insensitively. Only files directly inside
`res/input` are processed; subfolders are not scanned.

## Supported Image Formats

| Input format                 | How it is decoded        | Output |
|------------------------------|--------------------------|--------|
| JPG / JPEG                   | Java `ImageIO`           | JPEG   |
| PNG (including transparency) | Java `ImageIO`           | JPEG   |
| BMP                          | Java `ImageIO`           | JPEG   |
| WebP lossless (VP8L)         | Custom pure-Java decoder | JPEG   |
| WebP lossy (VP8)             | FFmpeg executable        | JPEG   |
| WebP lossy with alpha        | FFmpeg executable        | JPEG   |
| Animated WebP                | **Not supported**        | —      |

**Note:** FFmpeg is an external program, not a Java library. This project does not contain a pure-Java decoder for lossy
VP8.

## How It Works

1. Reads the image using Java `ImageIO`, the built-in custom VP8L decoder, or FFmpeg for VP8.
2. Finds the bounding area that is not white/near-white or fully transparent.
3. Expands the crop area by the configured padding, without extending beyond the original image boundaries.
4. Places the crop in the center of a white, square image; transparency is flattened onto white.
5. For products, scales the square to **1000 × 1000**. For logos, keeps the square's current dimensions.
6. Saves the result as a JPEG in the corresponding output directory.

## Configuration

Edit these constants in `SquareImageProcessor.java`:

```java
private static final int WHITE_THRESHOLD = 245;
private static final int PADDING = 20;
private static final int IMAGE_SIZE = 1000;
```

- **`WHITE_THRESHOLD`**: A pixel is considered white when all its RGB channels, after compositing onto white, are at
  least this value. **Lower values mean more aggressive cropping**.
- **`PADDING`**: Number of pixels added around the detected content **where space exists in the original image**; it is
  not a guaranteed minimum on the final canvas.
- **`IMAGE_SIZE`**: Width and height of the output **product** image. Does not resize logos.

JPEG output currently uses compression quality `1.0f` (the maximum setting exposed by Java `ImageIO`); JPEG is still a
lossy format.

## Troubleshooting

**`Cannot decode lossy VP8 WebP without FFmpeg`**  
Install FFmpeg, verify `ffmpeg -version`, then reopen your terminal or restart IntelliJ IDEA. If necessary, set
`FFMPEG_COMMAND` in `WebPReader.java` to the full executable path.

**`Animated WebP is not supported by this decoder`**  
Animated WebP is not currently supported. Convert the desired frame to a static image before processing.

**`Input folder is invalid: res/input`**  
Create `res/input` relative to the working directory from which you run Java.

**`Could not read image`**  
Check that the file is a valid image in one of the supported formats.

**Subject is cropped too closely or retains extra background**  
Adjust `WHITE_THRESHOLD` and `PADDING`. Cropping works best with white or near-white backgrounds; shadows and off-white
areas can affect detection.

## Notes

- Only supported image files in the immediate input folder are processed.
- Logos remain square but are **not resized to 1000 × 1000**.
- Output JPEGs do not preserve transparency; transparent areas appear white.
- Existing output files with the same generated name may be overwritten.
- The custom decoder rejects animated WebP and limits its decoded images to **40 million pixels**.

## Perfect For

- E-commerce product image preparation
- Catalog image standardization
- Batch conversion of product WebP images to JPEG

## License

Free to use and modify.
