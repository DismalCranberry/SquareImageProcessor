# Square Image Processor

A simple Java tool for preparing product images by automatically cropping white margins, centering the subject, converting to a square format, and resizing to a fixed size.

## Features

* Automatically removes white/empty margins around images
* Keeps the main subject centered
* Adds configurable padding after cropping
* Converts images to a square format (1:1)
* Resizes output to **1000 × 1000**
* Supports **JPG** and **PNG**
* High-quality scaling and JPEG compression

## Folder Structure

```
res/
  input/    → place your original images here
  output/   → processed images will be saved here
```

## How It Works

1. Loads each image from the `input` folder
2. Detects and removes white borders
3. Adds a small padding around the subject
4. Places the image on a white square canvas
5. Resizes the result to 1000×1000
6. Saves the processed image in the `output` folder

## Usage

1. Put your images inside:

```
res/input
```

2. Run the program:

```
SquareImageProcessor.java
```

3. Get processed images in:

```
res/output
```

## Configuration

You can tweak these values in the code:

```java
private static final int WHITE_THRESHOLD = 245;
private static final int PADDING = 20;
```

* **WHITE_THRESHOLD**
  Controls how "white" a pixel must be to be considered background
  (lower = more aggressive cropping)

* **PADDING**
  Adds space back around the subject after cropping

## Notes
* Works best with **white or near-white backgrounds**
* Images with shadows or off-white backgrounds may need threshold adjustments
* Transparent PNGs are flattened onto a white background

## Perfect for:
* E-commerce product images
* Catalog preparation

##  License
Free to use and modify.
