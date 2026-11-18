"""Lytro camera roll viewer.

Connects to a first-generation Lytro camera over its WiFi hotspot, shows the
camera roll as thumbnails, and renders the light field of a picture when its
thumbnail is clicked: refocus by clicking into the image, change the depth of
field with a slider, view the estimated depth map, export the rendering and
the original RAW.

Run:  .venv\\Scripts\\python lytro_viewer.py
"""
from __future__ import annotations

import json
import queue
import threading
import time
import tkinter as tk
import hashlib
from collections import OrderedDict
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

import numpy as np
from PIL import Image, ImageTk

from lytro.camera import (DEFAULT_HOST, Cancelled, LytroCamera, Picture,
                          thumbnail_to_rgb)
from lytro.lightfield import LightField

CACHE = Path(__file__).resolve().parent / "cache"
THUMB = 128
MAX_APERTURE = LightField.RADIUS
MEMORY_CACHE = 4  # decoded pictures kept in RAM (~160 MB each)
FULL_APERTURE_F = 2.0  # the Lytro lens is a constant f/2


# --------------------------------------------------------------------- cache
class Cache:
    """Files downloaded from the camera, keyed by picture id (sha1)."""

    def __init__(self, root: Path):
        self.root = root
        root.mkdir(parents=True, exist_ok=True)

    def path(self, pic: Picture, ext: str) -> Path:
        return self.root / pic.picture_id / f"{pic.name}.{ext}"

    def get(self, pic: Picture, ext: str) -> bytes | None:
        p = self.path(pic, ext)
        return p.read_bytes() if p.exists() else None

    def put(self, pic: Picture, ext: str, data: bytes):
        p = self.path(pic, ext)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_suffix(p.suffix + ".part")
        tmp.write_bytes(data)
        tmp.replace(p)

    def lf_path(self, pic: Picture) -> Path:
        """Decoded light field + depth map (see LightField.save)."""
        return self.path(pic, "lf.npz")

    def save_index(self, pics: list[Picture]):
        (self.root / "index.json").write_text(json.dumps([asdict(p) for p in pics], indent=1))

    def load_index(self) -> list[Picture]:
        p = self.root / "index.json"
        if not p.exists():
            return []
        try:
            return [Picture(**d) for d in json.loads(p.read_text())]
        except (ValueError, TypeError):
            return []


# ------------------------------------------------------------------- workers
class CameraWorker(threading.Thread):
    """Owns the camera connection; runs one job at a time (the camera does
    not cope with concurrent or rapid-fire requests). Results are posted to
    the UI queue as (event, payload) tuples."""

    def __init__(self, ui: queue.Queue, cache: Cache):
        super().__init__(daemon=True)
        self.ui, self.cache = ui, cache
        self.jobs: queue.Queue = queue.Queue()
        self.cam: LytroCamera | None = None
        self.raw_generation = 0  # bumping this cancels a running RAW download

    def run(self):
        while True:
            job, arg = self.jobs.get()
            try:
                getattr(self, "_job_" + job)(arg)
            except Cancelled:
                pass
            except Exception as e:  # report everything else to the UI
                self.ui.put(("error", (job, arg, e)))

    def submit(self, job: str, arg=None):
        self.jobs.put((job, arg))

    def _job_connect(self, host: str):
        if self.cam:
            self.cam.close()
        self.ui.put(("status", f"Connecting to {host}…"))
        self.cam = LytroCamera(host)
        info = self.cam.hardware_info()
        pics = self.cam.list_pictures()
        self.cache.save_index(pics)
        self.ui.put(("connected", (info, pics)))
        for pic in pics:  # thumbnails are tiny (32 KB): fetch them all
            data = self.cache.get(pic, "128")
            if data is None:
                data = self.cam.read_thumbnail(pic)
                self.cache.put(pic, "128", data)
            self.ui.put(("thumb", (pic, data)))
        self.ui.put(("status", f"{len(pics)} pictures on the camera"))

    def _job_raw(self, pic: Picture):
        gen = self.raw_generation
        meta = self.cache.get(pic, "TXT")
        raw = self.cache.get(pic, "RAW")
        if meta is None or raw is None:
            if self.cam is None:
                raise RuntimeError("not connected to the camera, and the RAW is not cached")
            if meta is None:
                meta = self.cam.read_metadata(pic)
                self.cache.put(pic, "TXT", meta)
            if raw is None:
                t0 = time.time()

                def progress(done, total):
                    rate = done / max(time.time() - t0, 1e-3)
                    self.ui.put(("download", (pic, done, total, (total - done) / max(rate, 1))))

                raw = self.cam.read_raw(pic, progress, cancel=lambda: gen != self.raw_generation)
                self.cache.put(pic, "RAW", raw)
        if gen == self.raw_generation:
            self.ui.put(("raw", (pic, raw, meta)))


class Processor(threading.Thread):
    """Decodes light fields and computes depth maps off the UI thread.
    Results are saved to disk (lf_path) so a picture is only ever decoded
    once. Only the newest request matters; older ones are abandoned (their
    decoded views are already saved, so nothing is lost)."""

    def __init__(self, ui: queue.Queue):
        super().__init__(daemon=True)
        self.ui = ui
        self.cv = threading.Condition()
        self.request = None
        self.generation = 0

    def submit(self, key, meta: bytes, lf_path: Path, raw: bytes | None = None,
               raw_path: Path | None = None, lf: LightField | None = None):
        """Provide raw bytes, a raw_path, or an already decoded lf (which
        then only needs its depth map)."""
        with self.cv:
            self.generation += 1
            self.request = (self.generation, key, meta, lf_path, raw, raw_path, lf)
            self.cv.notify()

    def run(self):
        while True:
            with self.cv:
                while self.request is None:
                    self.cv.wait()
                gen, key, meta, lf_path, raw, raw_path, lf = self.request
                self.request = None

            def stale():
                return gen != self.generation

            def depth_progress(f):
                if stale():
                    raise Cancelled()
                self.ui.put(("depth_progress", (key, f)))

            try:
                if lf is None:
                    meta_d = json.loads(meta)
                    lf = LightField.load(lf_path, meta_d) if lf_path.exists() else None
                    if lf is None:
                        self.ui.put(("status", "Decoding light field…"))
                        if raw is None:
                            raw = raw_path.read_bytes()
                        lf = LightField(raw, meta_d)
                        lf_path.parent.mkdir(parents=True, exist_ok=True)
                        lf.save(lf_path)  # keep the decode even if depth gets interrupted
                    self.ui.put(("lightfield", (key, lf)))
                if stale():
                    continue
                if lf.depth is None:
                    lf.compute_depth(progress=depth_progress)
                    lf.save(lf_path)
                self.ui.put(("depth", (key, lf)))
            except Cancelled:
                pass
            except Exception as e:
                self.ui.put(("error", ("decode", key, e)))


# ------------------------------------------------------------------------ UI
class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Lytro Viewer")
        self.geometry("1280x820")
        self.minsize(900, 600)
        self.configure(bg="#1e1e1e")

        self.ui: queue.Queue = queue.Queue()
        self.cache = Cache(CACHE)
        self.camera = CameraWorker(self.ui, self.cache)
        self.camera.start()
        self.processor = Processor(self.ui)
        self.processor.start()

        self.pictures: list[Picture] = []
        self.thumb_widgets: dict[str, tk.Label] = {}
        self.thumb_images: dict[str, ImageTk.PhotoImage] = {}
        self.current: Picture | None = None
        self.current_key = None
        self.lf: LightField | None = None
        self.rendered: np.ndarray | None = None  # last render, sRGB uint8
        self.photo: ImageTk.PhotoImage | None = None
        self.view_box = (0, 0, 1.0)  # x0, y0, scale of the image on the canvas
        self.render_scheduled = False
        self.pending_click: tuple[float, float] | None = None
        self.anim: list[float] = []
        self.raw_failed = False
        # decoded pictures kept in memory (most recent last), where their
        # disk cache lives, and per-picture focus / depth-of-field settings
        self.lf_memory: OrderedDict = OrderedDict()
        self.lf_paths: dict = {}
        self.view_state: dict = {}

        self._build_ui()
        self.after(50, self._poll)
        self._load_cached_index()

    # ---------------------------------------------------------------- layout
    def _build_ui(self):
        style = ttk.Style(self)
        style.theme_use("clam")
        bg, fg, panel = "#1e1e1e", "#e6e6e6", "#262626"
        style.configure(".", background=bg, foreground=fg, fieldbackground="#333", bordercolor="#444")
        style.configure("TButton", background="#333", padding=(10, 4))
        style.map("TButton", background=[("active", "#444"), ("disabled", "#2a2a2a")],
                  foreground=[("disabled", "#777")])
        style.configure("Panel.TFrame", background=panel)
        style.configure("Panel.TLabel", background=panel)
        style.configure("Dim.TLabel", foreground="#9a9a9a")
        style.configure("Horizontal.TProgressbar", background="#4a90d9", troughcolor="#333")
        style.configure("Horizontal.TScale", troughcolor="#3a3a3a", background="#9a9a9a",
                        bordercolor="#3a3a3a", lightcolor="#9a9a9a", darkcolor="#9a9a9a")
        style.map("TCombobox", fieldbackground=[("readonly", "#333")], foreground=[("readonly", fg)],
                  selectbackground=[("readonly", "#333")], selectforeground=[("readonly", fg)])
        self.option_add("*TCombobox*Listbox.background", "#333")
        self.option_add("*TCombobox*Listbox.foreground", fg)
        style.configure("Seg.Toolbutton", background="#2d2d2d", foreground="#bbb", padding=(12, 4),
                        relief="flat", borderwidth=1)
        style.map("Seg.Toolbutton", background=[("selected", "#4a90d9"), ("active", "#3a3a3a")],
                  foreground=[("selected", "#ffffff"), ("disabled", "#666")])
        style.configure("TCheckbutton", background=panel)
        style.map("TCheckbutton", background=[("active", panel)])

        top = ttk.Frame(self, padding=(10, 8))
        top.pack(fill="x")
        ttk.Label(top, text="Camera IP").pack(side="left")
        self.host_var = tk.StringVar(value=DEFAULT_HOST)
        ttk.Entry(top, textvariable=self.host_var, width=14).pack(side="left", padx=(6, 6))
        ttk.Button(top, text="Connect", command=self.connect).pack(side="left")
        ttk.Button(top, text="Open RAW file…", command=self.open_local).pack(side="left", padx=(6, 0))
        self.cam_label = ttk.Label(top, text="Not connected", style="Dim.TLabel")
        self.cam_label.pack(side="right")

        body = ttk.PanedWindow(self, orient="horizontal")
        body.pack(fill="both", expand=True)

        # camera roll
        roll = ttk.Frame(body, style="Panel.TFrame")
        body.add(roll, weight=0)
        ttk.Label(roll, text="Camera roll", style="Panel.TLabel", padding=(10, 8),
                  font=("Segoe UI", 10, "bold")).pack(anchor="w")
        self.roll_canvas = tk.Canvas(roll, bg="#262626", highlightthickness=0, width=2 * (THUMB + 24) + 10)
        sb = ttk.Scrollbar(roll, orient="vertical", command=self.roll_canvas.yview)
        self.roll_canvas.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.roll_canvas.pack(side="left", fill="both", expand=True)
        self.grid_frame = tk.Frame(self.roll_canvas, bg="#262626")
        self.roll_canvas.create_window((0, 0), window=self.grid_frame, anchor="nw")
        self.grid_frame.bind("<Configure>", lambda e: self.roll_canvas.configure(scrollregion=self.roll_canvas.bbox("all")))
        self.roll_canvas.bind_all("<MouseWheel>", self._on_wheel)

        # viewer
        viewer = ttk.Frame(body)
        body.add(viewer, weight=1)
        self.info_label = ttk.Label(viewer, text="Click a thumbnail to download and render it",
                                    padding=(12, 8), font=("Segoe UI", 10))
        self.info_label.pack(fill="x")
        self.canvas = tk.Canvas(viewer, bg="#141414", highlightthickness=0, cursor="crosshair")
        self.canvas.pack(fill="both", expand=True, padx=10)
        self.canvas.bind("<Configure>", lambda e: self._show())
        self.canvas.bind("<Button-1>", self._on_click)

        ctl = ttk.Frame(viewer, padding=(12, 10))
        ctl.pack(fill="x")
        ctl.columnconfigure(1, weight=1)

        ttk.Label(ctl, text="Focus").grid(row=0, column=0, sticky="w")
        self.focus_var = tk.DoubleVar(value=0.0)
        self.focus_scale = ttk.Scale(ctl, from_=LightField.SLOPE_RANGE[0], to=LightField.SLOPE_RANGE[1],
                                     variable=self.focus_var, command=lambda v: self._focus_moved())
        self.focus_scale.grid(row=0, column=1, sticky="ew", padx=10)
        self.focus_label = ttk.Label(ctl, text="", width=24, style="Dim.TLabel")
        self.focus_label.grid(row=0, column=2, sticky="w")

        ttk.Label(ctl, text="Depth of field").grid(row=1, column=0, sticky="w", pady=(6, 0))
        self.dof_var = tk.DoubleVar(value=0.0)  # 0 = shallow (full aperture), 1 = deep (pinhole)
        ttk.Scale(ctl, from_=0.0, to=1.0, variable=self.dof_var,
                  command=lambda v: self._schedule_render()).grid(row=1, column=1, sticky="ew", padx=10, pady=(6, 0))
        self.dof_label = ttk.Label(ctl, text="", width=24, style="Dim.TLabel")
        self.dof_label.grid(row=1, column=2, sticky="w", pady=(6, 0))

        row = ttk.Frame(ctl)
        row.grid(row=2, column=0, columnspan=3, sticky="ew", pady=(10, 0))
        ttk.Label(row, text="View").pack(side="left", padx=(0, 8))
        self.mode_var = tk.StringVar(value="photo")
        self.mode_buttons = {}
        for mode, label in (("photo", "Photo"), ("depth", "Depth map"), ("overlay", "Overlay")):
            b = ttk.Radiobutton(row, text=label, value=mode, variable=self.mode_var,
                                style="Seg.Toolbutton", command=self._mode_changed)
            b.pack(side="left")
            self.mode_buttons[mode] = b
        self.export_raw_btn = ttk.Button(row, text="Export RAW…", command=self.export_raw, state="disabled")
        self.export_raw_btn.pack(side="right")
        self.export_depth_btn = ttk.Button(row, text="Export depth map…", command=self.export_depth, state="disabled")
        self.export_depth_btn.pack(side="right", padx=6)
        self.export_img_btn = ttk.Button(row, text="Export view…", command=self.export_image, state="disabled")
        self.export_img_btn.pack(side="right")
        self.scale_var = tk.StringVar(value="2×")
        ttk.Combobox(row, textvariable=self.scale_var, values=["1×", "2×", "3×", "4×"], width=4,
                     state="readonly").pack(side="right", padx=6)
        ttk.Label(row, text="Export size", style="Dim.TLabel").pack(side="right")

        status = ttk.Frame(self, padding=(10, 4))
        status.pack(fill="x")
        self.progress = ttk.Progressbar(status, length=220, mode="determinate")
        self.progress.pack(side="right")
        self.status_label = ttk.Label(status, text="", style="Dim.TLabel")
        self.status_label.pack(side="left")
        self._update_labels()

    def _on_wheel(self, e):
        w = self.winfo_containing(e.x_root, e.y_root)
        while w is not None:
            if w is self.roll_canvas:
                self.roll_canvas.yview_scroll(int(-e.delta / 120), "units")
                return
            w = w.master

    # ------------------------------------------------------- camera roll
    def _load_cached_index(self):
        pics = self.cache.load_index()
        if not pics:
            self.status("Connect to the camera's WiFi, then press Connect")
            return
        self._set_pictures(pics)
        for pic in pics:
            data = self.cache.get(pic, "128")
            if data:
                self._set_thumb(pic, data)
        self.status(f"Showing {len(pics)} cached pictures. Press Connect to refresh from the camera")

    def connect(self):
        self.camera.submit("connect", self.host_var.get().strip())

    def _set_pictures(self, pics: list[Picture]):
        for w in self.grid_frame.winfo_children():
            w.destroy()
        self.thumb_widgets.clear()
        self.thumb_images.clear()
        self.pictures = pics
        placeholder = ImageTk.PhotoImage(Image.new("RGB", (THUMB, THUMB), "#333"))
        self._placeholder = placeholder
        for i, pic in enumerate(pics):
            cell = tk.Frame(self.grid_frame, bg="#262626", padx=6, pady=6)
            cell.grid(row=i // 2, column=i % 2)
            img = tk.Label(cell, image=placeholder, bg="#262626", bd=0, highlightthickness=3,
                           highlightbackground="#262626", cursor="hand2")
            img.pack()
            cap = tk.Label(cell, text=self._caption(pic), bg="#262626", fg="#bbb", font=("Segoe UI", 8))
            cap.pack()
            for w in (img, cap, cell):
                w.bind("<Button-1>", lambda e, p=pic: self.select(p))
            self.thumb_widgets[pic.picture_id] = img
            img.caption = cap
        self._mark_selected()

    def _caption(self, pic: Picture) -> str:
        try:
            dt = datetime.fromisoformat(pic.taken.replace("Z", "+00:00")).astimezone()
            when = dt.strftime("%d %b %Y %H:%M")
        except ValueError:
            when = pic.taken
        dot = "  ●" if self.cache.path(pic, "RAW").exists() else ""
        return f"{pic.name}{dot}\n{when}"

    def _set_thumb(self, pic: Picture, data: bytes):
        w = self.thumb_widgets.get(pic.picture_id)
        if not w:
            return
        im = ImageTk.PhotoImage(Image.fromarray(thumbnail_to_rgb(data)))
        self.thumb_images[pic.picture_id] = im
        w.configure(image=im)

    def _mark_selected(self):
        for pid, w in self.thumb_widgets.items():
            sel = self.current is not None and pid == self.current.picture_id
            w.configure(highlightbackground="#4a90d9" if sel else "#262626")

    # ---------------------------------------------------------- selection
    def _begin(self, key, title: str, preview: np.ndarray | None):
        """Switch the viewer to another picture."""
        if self.lf is not None and self.current_key is not None:
            self.view_state[self.current_key] = (self.focus_var.get(), self.dof_var.get())
        self.current_key = key
        self.lf = None
        self.anim = []
        self.pending_click = None
        self._mark_selected()
        self._set_controls(False)
        self.progress.configure(value=0)
        self.info_label.configure(text=title)
        self.rendered = preview
        self._show()

    def _from_memory(self, key) -> bool:
        """Show a picture decoded earlier in this session, if still in RAM."""
        lf = self.lf_memory.get(key)
        if lf is None:
            return False
        self.lf_memory.move_to_end(key)
        self._show_lightfield(key, lf)
        if lf.depth is None:  # its depth pass was interrupted: finish it
            self.processor.submit(key, b"", self.lf_paths[key], lf=lf)
        else:
            self._depth_ready(key, lf)
        return True

    def select(self, pic: Picture):
        if self.current and pic.picture_id == self.current.picture_id and not self.raw_failed:
            return  # already shown, or still downloading/decoding
        self.raw_failed = False
        self.current = pic
        key = pic.picture_id
        data = self.cache.get(pic, "128")
        self._begin(key, f"{pic.name}   ·   {self._caption(pic).splitlines()[1]}",
                    thumbnail_to_rgb(data) if data else None)
        if self._from_memory(key):
            return
        lf_path = self.cache.lf_path(pic)
        self.lf_paths[key] = lf_path
        meta = self.cache.get(pic, "TXT")
        raw_path = self.cache.path(pic, "RAW")
        if meta is not None and raw_path.exists():
            # everything is on disk: no need to touch the camera (and a
            # download of another picture can keep running)
            self.processor.submit(key, meta, lf_path, raw_path=raw_path)
            return
        self.camera.raw_generation += 1  # cancels a running download
        self.status(f"Downloading {pic.name}.RAW (16 MB) from the camera…")
        self.progress.configure(value=0, maximum=1)
        self.camera.submit("raw", pic)

    def open_local(self):
        path = filedialog.askopenfilename(title="Open Lytro RAW", filetypes=[("Lytro RAW", "*.RAW *.raw")])
        if not path:
            return
        raw_p = Path(path)
        meta_p = next((p for p in (raw_p.with_suffix(".TXT"), raw_p.with_suffix(".txt")) if p.exists()), None)
        if meta_p is None:
            messagebox.showerror("Missing metadata", f"Expected {raw_p.with_suffix('.TXT').name} next to the RAW file.")
            return
        self.current = None
        key = str(raw_p.resolve())
        self._begin(key, raw_p.name, None)
        if self._from_memory(key):
            return
        raw = raw_p.read_bytes()
        lf_path = CACHE / "local" / (hashlib.sha1(raw).hexdigest() + ".lf.npz")
        self.lf_paths[key] = lf_path
        self.processor.submit(key, meta_p.read_bytes(), lf_path, raw=raw)

    # ---------------------------------------------------------- event loop
    def _poll(self):
        try:
            while True:
                ev, arg = self.ui.get_nowait()
                getattr(self, "_ev_" + ev)(arg)
        except queue.Empty:
            pass
        self.after(40, self._poll)

    def status(self, text: str):
        self.status_label.configure(text=text)

    def _ev_status(self, text):
        self.status(text)

    def _ev_error(self, arg):
        job, what, e = arg
        if job == "connect":
            self.cam_label.configure(text="Not connected")
            self.status(f"Could not reach the camera at {what}: {e}. Is this PC on the camera's WiFi?")
        elif job == "raw":
            self.raw_failed = True  # clicking the thumbnail again retries
            self.status(f"Download of {what.name} failed: {e}. Click the thumbnail to retry")
            self.progress.configure(value=0)
        else:
            self.status(f"Error ({job}): {e}")

    def _ev_connected(self, arg):
        info, pics = arg
        self.cam_label.configure(text=f"{info.manufacturer}  ·  S/N {info.serial}  ·  firmware {info.firmware}")
        self._set_pictures(pics)
        self.status(f"Loading {len(pics)} thumbnails…")

    def _ev_thumb(self, arg):
        self._set_thumb(*arg)

    def _ev_download(self, arg):
        pic, done, total, eta = arg
        if self.current and pic.picture_id == self.current.picture_id:
            self.progress.configure(maximum=total, value=done)
            self.status(f"Downloading {pic.name}.RAW: {done / 1e6:.1f} / {total / 1e6:.1f} MB, about {eta:.0f} s left")

    def _ev_raw(self, arg):
        pic, raw, meta = arg
        w = self.thumb_widgets.get(pic.picture_id)
        if w:
            w.caption.configure(text=self._caption(pic))
        if self.current and pic.picture_id == self.current.picture_id:
            self.progress.configure(value=0)
            self.processor.submit(pic.picture_id, meta, self.cache.lf_path(pic), raw=raw)

    def _ev_lightfield(self, arg):
        key, lf = arg
        self.lf_memory[key] = lf
        self.lf_memory.move_to_end(key)
        while len(self.lf_memory) > MEMORY_CACHE:
            self.lf_memory.popitem(last=False)
        if key == self.current_key:
            self._show_lightfield(key, lf)

    def _show_lightfield(self, key, lf: LightField):
        self.lf = lf
        focus, dof = self.view_state.get(key, (0.0, 0.0))
        self.focus_var.set(focus)
        self.dof_var.set(dof)
        self._set_controls(True)
        if lf.depth is None:
            self.status("Estimating depth… (you can already refocus with the slider)")
            self.progress.configure(maximum=1, value=0)
        self._schedule_render()

    def _ev_depth_progress(self, arg):
        key, f = arg
        if key == self.current_key:
            self.progress.configure(maximum=1, value=f)

    def _ev_depth(self, arg):
        key, lf = arg
        if key == self.current_key:
            self._depth_ready(key, lf)

    def _depth_ready(self, key, lf: LightField):
        self.progress.configure(value=0)
        self.export_depth_btn.configure(state="normal")
        self.status("Ready: click into the image to focus there")
        if self.pending_click:
            self._focus_at(*self.pending_click)
            self.pending_click = None
        elif self.mode_var.get() != "photo":
            self._schedule_render()

    def _set_controls(self, on: bool):
        state = "normal" if on else "disabled"
        self.export_img_btn.configure(state=state)
        self.export_raw_btn.configure(state=state if self.current else "disabled")
        if not on:
            self.export_depth_btn.configure(state="disabled")

    # ------------------------------------------------------------ rendering
    def _aperture(self) -> float:
        return MAX_APERTURE * (1.0 - self.dof_var.get())

    def _update_labels(self):
        s = self.focus_var.get()
        lo, hi = LightField.SLOPE_RANGE
        where = "far" if s < lo / 3 else "near" if s > hi / 3 else "mid"
        self.focus_label.configure(text=f"{s:+.2f}  ({where})")
        a = self._aperture()
        if a < 1:
            self.dof_label.configure(text="all in focus (pinhole)")
        else:
            self.dof_label.configure(text=f"≈ f/{FULL_APERTURE_F * MAX_APERTURE / a:.1f}")

    def _focus_moved(self):
        self.anim = []  # user takes over from a running animation
        self._schedule_render()

    def _schedule_render(self):
        self._update_labels()
        if not self.render_scheduled:
            self.render_scheduled = True
            self.after(10, self._render)

    def _render(self):
        self.render_scheduled = False
        if self.lf is None:
            return
        self.rendered = self._view_image()
        self._show()

    def _view_image(self) -> np.ndarray:
        """The image for the current view mode (falls back to the photo
        while the depth map is still being computed)."""
        mode = self.mode_var.get()
        if mode == "depth" and self.lf.depth is not None:
            return self.lf.depth_rgb8()
        photo = self.lf.to_srgb8(self.lf.refocus(self.focus_var.get(), self._aperture()))
        if mode == "overlay" and self.lf.depth is not None:
            mix = 0.45 * photo.astype(np.float32) + 0.55 * self.lf.depth_rgb8().astype(np.float32)
            return mix.clip(0, 255).astype(np.uint8)
        return photo

    def _mode_changed(self):
        if self.lf is not None and self.lf.depth is None and self.mode_var.get() != "photo":
            self.status("Depth map still computing. It will appear as soon as it's ready…")
        self._schedule_render()

    def _show(self):
        self.canvas.delete("img")
        if self.rendered is None:
            return
        cw, ch = max(self.canvas.winfo_width(), 1), max(self.canvas.winfo_height(), 1)
        h, w = self.rendered.shape[:2]
        scale = min(cw / w, ch / h)
        size = (max(int(w * scale), 1), max(int(h * scale), 1))
        im = Image.fromarray(self.rendered).resize(size, Image.LANCZOS)
        self.photo = ImageTk.PhotoImage(im)
        x0, y0 = (cw - size[0]) // 2, (ch - size[1]) // 2
        self.view_box = (x0, y0, scale)
        self.canvas.create_image(x0, y0, image=self.photo, anchor="nw", tags="img")
        self.canvas.tag_lower("img")

    # ------------------------------------------------------------- focusing
    def _on_click(self, e):
        if self.lf is None:
            return
        x0, y0, scale = self.view_box
        x, y = (e.x - x0) / scale, (e.y - y0) / scale
        if not (0 <= x < self.lf.w and 0 <= y < self.lf.h):
            return
        self._marker(e.x, e.y)
        if self.lf.depth is None:
            self.pending_click = (x, y)
            self.status("Depth map still computing. Will focus there as soon as it's ready…")
            return
        self._focus_at(x, y)

    def _focus_at(self, x: float, y: float):
        target = self.lf.focus_slope_at(x, y)
        start = self.focus_var.get()
        n = 6
        self.anim = [start + (target - start) * (1 - (1 - i / n) ** 2) for i in range(1, n + 1)]
        self._animate()

    def _animate(self):
        if not self.anim:
            return
        self.focus_var.set(self.anim.pop(0))
        self._update_labels()
        self._render()
        if self.anim:
            self.after(16, self._animate)

    def _marker(self, x, y):
        self.canvas.delete("marker")
        r = 14
        self.canvas.create_oval(x - r, y - r, x + r, y + r, outline="#ffffff", width=2, tags="marker")
        self.canvas.create_oval(x - 2, y - 2, x + 2, y + 2, fill="#ffffff", outline="", tags="marker")
        self.after(700, lambda: self.canvas.delete("marker"))

    # --------------------------------------------------------------- export
    def _base_name(self) -> str:
        if self.current:
            return self.current.name
        return Path(str(self.current_key)).stem if self.current_key else "lytro"

    def _export_scale(self) -> int:
        return int(self.scale_var.get().rstrip("×"))

    def _scaled(self, img: np.ndarray) -> Image.Image:
        im = Image.fromarray(img)
        f = self._export_scale()
        return im.resize((im.width * f, im.height * f), Image.LANCZOS) if f > 1 else im

    def export_image(self):
        """Export exactly what the viewer shows (photo, depth map or overlay)."""
        if self.lf is None:
            return
        mode = self.mode_var.get() if self.lf.depth is not None else "photo"
        suffix = {"photo": f"focus{self.focus_var.get():+.2f}", "depth": "depth", "overlay": "overlay"}[mode]
        path = filedialog.asksaveasfilename(
            title="Export view", defaultextension=".png",
            initialfile=f"{self._base_name()}_{suffix}.png",
            filetypes=[("PNG", "*.png"), ("JPEG", "*.jpg *.jpeg"), ("TIFF", "*.tif *.tiff")])
        if not path:
            return
        im = self._scaled(self._view_image())
        kw = {"quality": 95} if path.lower().endswith((".jpg", ".jpeg")) else {}
        im.save(path, **kw)
        self.status(f"Saved {path} ({im.width}×{im.height})")

    def export_depth(self):
        if self.lf is None or self.lf.depth is None:
            return
        path = filedialog.asksaveasfilename(
            title="Export depth map", defaultextension=".png",
            initialfile=f"{self._base_name()}_depth.png", filetypes=[("PNG", "*.png")])
        if not path:
            return
        self._scaled(self.lf.depth_rgb8()).save(path)
        # plus a 16-bit greyscale version at native resolution (near = bright)
        d = self.lf.depth
        lo, hi = float(d.min()), float(d.max())
        g16 = ((d - lo) / (hi - lo + 1e-9) * 65535).astype(np.uint16)
        p16 = str(Path(path).with_name(Path(path).stem + "_16bit.png"))
        Image.fromarray(g16).save(p16)
        self.status(f"Saved {path} (heatmap) and {Path(p16).name} (16-bit values)")

    def export_raw(self):
        if not self.current:
            return
        src = self.cache.path(self.current, "RAW")
        if not src.exists():
            return
        path = filedialog.asksaveasfilename(
            title="Export RAW light field", defaultextension=".RAW",
            initialfile=f"{self.current.name}.RAW", filetypes=[("Lytro RAW", "*.RAW")])
        if not path:
            return
        dst = Path(path)
        dst.write_bytes(src.read_bytes())
        meta = self.cache.path(self.current, "TXT")
        dst.with_suffix(".TXT").write_bytes(meta.read_bytes())
        self.status(f"Saved {dst.name} and {dst.with_suffix('.TXT').name} (metadata) to {dst.parent}")


def main():
    try:  # crisp rendering on high-DPI Windows displays
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass
    App().mainloop()


if __name__ == "__main__":
    main()
