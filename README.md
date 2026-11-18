# Lytro Viewer

A desktop viewer for the first-generation Lytro light-field camera that works over the camera's own WiFi hotspot.

## Run

```
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python lytro_viewer.py
```

Join the camera's WiFi (`lytro.camera.NNN`), then press **Connect**.

## Using it

- **Camera roll**: lists every picture on the camera, with its thumbnail. Thumbnails are small (32 KB each).
- **Opening a picture**: click a thumbnail. The 16 MB RAW downloads only then (about 25 s over WiFi). Each picture is downloaded, decoded and depth-mapped only once:
  - **On disk:** `cache/` keeps the RAW plus the decoded light field and depth map (`.lf.npz`, about 32 MB). Reopening a picture after a restart takes about 1.5 s.
  - **In memory:** the last 4 opened pictures, so switching between them is instant. Each picture keeps its own focus and depth-of-field settings during the session.
  - A ● next to a name means its RAW is cached.
- **Refocusing**: click anywhere in the image to focus there, or use the **Focus** slider.
- **Depth of field**: the slider goes from f/2 (the real lens) to "pinhole" (everything sharp).
- **View: Photo / Depth map / Overlay**: switches between the refocused photo, the depth heatmap on its own (blue = far, red = near), and the heatmap blended over the photo. Clicking into the image refocuses in every mode.
- **Exporting**:
  - **Export view** saves exactly what is on screen (photo, depth map or overlay) as PNG, JPEG or TIFF, at 1–4× the native size.
  - **Export depth map** saves the heatmap PNG at the chosen size, plus a 16-bit greyscale PNG with the raw depth values.
  - **Export RAW** saves the original `.RAW` plus its `.TXT` metadata.
- **Open RAW file…** renders a `.RAW` + `.TXT` pair from disk, without the camera.

## Files

- `lytro/camera.py`: the WiFi protocol client. See [PROTOCOL.md](PROTOCOL.md).
- `lytro/lightfield.py`: decodes the light field (Bayer demosaic, hexagonal microlens grid, sub-aperture views), refocuses with a Fourier shift-and-add, and estimates depth.
- `lytro_viewer.py`: the Tkinter GUI.

## Notes

- The native rendering is about 325×325 px, one pixel per microlens. That's the real resolution of a basic shift-and-add renderer. Lytro's own software reached about 1080 px with super-resolution.
- The camera is fragile. The client keeps one connection open and only ever sends commands that are known to be safe.
