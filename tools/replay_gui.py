import json
import os
import queue
import shutil
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

try:
    from PIL import Image, ImageTk
except Exception:
    Image = None
    ImageTk = None


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JAVA = Path(r"C:\Program Files\Zulu\zulu-21\bin\java.exe")
DEFAULT_JAR = REPO_ROOT / "build" / "libs" / "Radiance-ReplayCli-0.1.5-alpha-fabric-1.21.4-dev.jar"
DEFAULT_GAME_DIR = Path(r"F:\1\mc\.minecraft\versions\1.21.4 fabric")


class ApiClient:
    def __init__(self, port):
        self.base = f"http://127.0.0.1:{port}"

    def get(self, path):
        with urllib.request.urlopen(self.base + path, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))

    def post(self, path, payload, timeout=600):
        data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.base + path,
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            try:
                data = json.loads(body)
                detail = data.get("stack") or data.get("error") or body
            except Exception:
                detail = body
            raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc


class ReplayGui(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Radiance Replay Control")
        self.geometry("1180x760")
        self.minsize(980, 620)
        self.queue = queue.Queue()
        self.process = None
        self.api = None
        self.save_id = None
        self.output_root = None
        self.log_file = None
        self.closing = False
        self.daemon_ready = False
        self.process_exited = False
        self.preview_image = None
        self.pipeline_attributes = {}
        self.pipeline_attr_getters = {}
        self.module_attributes = {}
        self.module_attr_getters = {}
        self.rendered_images = []

        self.java_var = tk.StringVar(value=str(DEFAULT_JAVA))
        self.jar_var = tk.StringVar(value=str(DEFAULT_JAR))
        self.game_dir_var = tk.StringVar(value=str(DEFAULT_GAME_DIR))
        self.xmx_var = tk.StringVar(value="8g")
        self.width_var = tk.StringVar(value="1920")
        self.height_var = tk.StringVar(value="1080")
        self.port_var = tk.StringVar(value="17890")
        self.status_var = tk.StringVar(value="Ready")
        self.log_path_var = tk.StringVar(value="")

        self._build_start_screen()
        self.after(100, self._drain_queue)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_start_screen(self):
        self.start_frame = ttk.Frame(self, padding=12)
        self.start_frame.pack(fill=tk.BOTH, expand=True)

        settings = ttk.LabelFrame(self.start_frame, text="Launch", padding=10)
        settings.pack(fill=tk.X)
        self._path_row(settings, "Java", self.java_var, 0, file=True)
        self._path_row(settings, "Replay CLI jar", self.jar_var, 1, file=True)
        self._path_row(settings, "Game directory", self.game_dir_var, 2, directory=True)

        opts = ttk.Frame(settings)
        opts.grid(row=3, column=1, sticky="w", pady=(8, 0))
        self._small_entry(opts, "Memory -Xmx", self.xmx_var, 0)
        self._small_entry(opts, "Width", self.width_var, 1)
        self._small_entry(opts, "Height", self.height_var, 2)
        self._small_entry(opts, "HTTP port", self.port_var, 3)

        ttk.Button(settings, text="Refresh Saves", command=self.refresh_saves).grid(
            row=4, column=1, sticky="w", pady=(10, 0)
        )
        settings.columnconfigure(1, weight=1)

        saves_frame = ttk.LabelFrame(self.start_frame, text="Replay Saves", padding=10)
        saves_frame.pack(fill=tk.BOTH, expand=True, pady=(12, 0))
        self.saves = tk.Listbox(saves_frame, height=12)
        self.saves.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar = ttk.Scrollbar(saves_frame, command=self.saves.yview)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.saves.configure(yscrollcommand=scrollbar.set)
        self.saves.bind("<Double-Button-1>", lambda _event: self.open_selected_save())

        bottom = ttk.Frame(self.start_frame)
        bottom.pack(fill=tk.X, pady=(10, 0))
        ttk.Label(bottom, textvariable=self.status_var).pack(side=tk.LEFT)
        ttk.Button(bottom, text="Open Save", command=self.open_selected_save).pack(side=tk.RIGHT)
        self.refresh_saves()

    def _path_row(self, parent, label, variable, row, file=False, directory=False):
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", pady=3)
        ttk.Entry(parent, textvariable=variable).grid(row=row, column=1, sticky="ew", padx=6)
        command = lambda: self._browse(variable, file=file, directory=directory)
        ttk.Button(parent, text="Browse", command=command).grid(row=row, column=2, sticky="e")

    def _small_entry(self, parent, label, variable, col):
        frame = ttk.Frame(parent)
        frame.grid(row=0, column=col, padx=(0, 12))
        ttk.Label(frame, text=label).pack(anchor="w")
        ttk.Entry(frame, textvariable=variable, width=10).pack(anchor="w")

    def _browse(self, variable, file=False, directory=False):
        if file:
            value = filedialog.askopenfilename()
        elif directory:
            value = filedialog.askdirectory()
        else:
            value = ""
        if value:
            variable.set(value)
            if variable is self.game_dir_var:
                self.refresh_saves()

    def refresh_saves(self):
        self.saves.delete(0, tk.END)
        radiance = Path(self.game_dir_var.get()) / "radiance"
        saves_dir = radiance / "replay_captures" / "saves"
        if not saves_dir.exists():
            self.status_var.set(f"No saves directory: {saves_dir}")
            return
        count = 0
        for child in sorted(saves_dir.iterdir(), key=lambda p: p.name.lower()):
            if (child / "capture.sqlite").exists():
                self.saves.insert(tk.END, child.name)
                count += 1
        self.status_var.set(f"{count} save(s) found")

    def open_selected_save(self):
        selection = self.saves.curselection()
        if not selection:
            messagebox.showwarning("Save", "Select a save first.")
            return
        self.save_id = self.saves.get(selection[0])
        self.output_root = (
            Path(self.game_dir_var.get())
            / "radiance"
            / "replay_captures"
            / "gui_outputs"
            / self.save_id
        )
        self.output_root.mkdir(parents=True, exist_ok=True)
        self.log_file = self.output_root / "replay_gui_daemon.log"
        self.log_path_var.set(str(self.log_file))
        self._build_session_screen()
        self._start_daemon()

    def _build_session_screen(self):
        self.start_frame.destroy()
        root = ttk.Frame(self, padding=10)
        root.pack(fill=tk.BOTH, expand=True)
        self.session_frame = root

        top = ttk.Frame(root)
        top.pack(fill=tk.X)
        ttk.Label(top, text=f"Save: {self.save_id}").pack(side=tk.LEFT)
        ttk.Label(top, textvariable=self.status_var).pack(side=tk.RIGHT)

        vertical = ttk.PanedWindow(root, orient=tk.VERTICAL)
        vertical.pack(fill=tk.BOTH, expand=True, pady=(10, 0))

        panes = ttk.PanedWindow(vertical, orient=tk.HORIZONTAL)
        console = ttk.Frame(vertical, padding=6)
        vertical.add(panes, weight=4)
        vertical.add(console, weight=1)

        left = ttk.Frame(panes, padding=6)
        center = ttk.Frame(panes, padding=6)
        right = ttk.Frame(panes, padding=6)
        panes.add(left, weight=1)
        panes.add(center, weight=1)
        panes.add(right, weight=5)

        self._build_segments_panel(left)
        self._build_pipeline_panel(center)
        self._build_preview_panel(right)
        self._build_console_panel(console)

    def _build_segments_panel(self, parent):
        ttk.Label(parent, text="Segments").pack(anchor="w")
        self.segment_list = tk.Listbox(parent, height=6, exportselection=False)
        self.segment_list.pack(fill=tk.X, pady=(4, 8))
        self.segment_list.bind("<<ListboxSelect>>", lambda _e: self._on_segment_select())

        ttk.Label(parent, text="Frames").pack(anchor="w")
        frame_box = ttk.Frame(parent)
        frame_box.pack(fill=tk.BOTH, expand=True, pady=(4, 0))
        self.frame_list = tk.Listbox(frame_box, selectmode=tk.EXTENDED, exportselection=False)
        self.frame_list.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        frame_scroll = ttk.Scrollbar(frame_box, command=self.frame_list.yview)
        frame_scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.frame_list.configure(yscrollcommand=frame_scroll.set)
        ttk.Label(
            parent,
            text=(
                "Current readback may return a recently rendered frame instead of the exact "
                "selected frame. Render the same frame several times if you need a stable preview; "
                "multi-select outputs may not line up exactly with the selected frame numbers."
            ),
            wraplength=260,
            foreground="#8a5a00",
        ).pack(fill=tk.X, pady=(6, 0))
        buttons = ttk.Frame(parent)
        buttons.pack(fill=tk.X, pady=(8, 0))
        ttk.Button(buttons, text="Render Selected", command=self.render_selected).pack(side=tk.RIGHT)
        ttk.Button(buttons, text="Save Image", command=self.save_preview_image).pack(side=tk.RIGHT, padx=(0, 8))

        info_frame = ttk.LabelFrame(parent, text="Info", padding=6)
        info_frame.pack(fill=tk.X, pady=(10, 0))
        self.info_text = tk.Text(info_frame, height=6, wrap=tk.WORD)
        self.info_text.pack(fill=tk.BOTH, expand=True)

    def _build_pipeline_panel(self, parent):
        ttk.Label(parent, text="Pipeline").pack(anchor="w")
        ttk.Label(parent, text="Preset").pack(anchor="w", pady=(8, 0))
        self.preset_var = tk.StringVar()
        self.preset_combo = ttk.Combobox(
            parent,
            textvariable=self.preset_var,
            values=["render_pipeline.preset.rt_dlss", "render_pipeline.preset.rt_nrd",
                    "render_pipeline.preset.rt_nrd_fsr", "render_pipeline.preset.rt_nrd_xess"],
            state="readonly",
        )
        self.preset_combo.pack(fill=tk.X)

        ttk.Label(parent, text="Shader Pack").pack(anchor="w", pady=(8, 0))
        self.shader_var = tk.StringVar()
        self.shader_combo = ttk.Combobox(parent, textvariable=self.shader_var, state="readonly")
        self.shader_combo.pack(fill=tk.X)
        ttk.Button(parent, text="Apply Shader Pack", command=self.apply_shader_pack).pack(
            anchor="e", pady=(8, 0)
        )

        ttk.Button(parent, text="Apply Pipeline", command=self.apply_pipeline).pack(
            anchor="e", pady=(8, 0)
        )

        attrs = ttk.LabelFrame(parent, text="Shader Settings", padding=6)
        attrs.pack(fill=tk.BOTH, expand=True, pady=(10, 0))
        self.attr_canvas = tk.Canvas(attrs, highlightthickness=0)
        self.attr_scroll = ttk.Scrollbar(attrs, orient=tk.VERTICAL, command=self.attr_canvas.yview)
        self.attr_inner = ttk.Frame(self.attr_canvas)
        self.attr_inner.bind(
            "<Configure>",
            lambda _e: self.attr_canvas.configure(scrollregion=self.attr_canvas.bbox("all")),
        )
        self.attr_canvas.create_window((0, 0), window=self.attr_inner, anchor="nw")
        self.attr_canvas.configure(yscrollcommand=self.attr_scroll.set)
        self.attr_canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.attr_scroll.pack(side=tk.RIGHT, fill=tk.Y)

    def _build_preview_panel(self, parent):
        ttk.Label(parent, text="Preview").pack(anchor="w")
        self.preview = ttk.Label(parent, anchor="center")
        self.preview.pack(fill=tk.BOTH, expand=True, pady=(4, 8))
        ttk.Label(parent, text="Images").pack(anchor="w")
        images_frame = ttk.Frame(parent)
        images_frame.pack(fill=tk.BOTH, expand=False)
        self.image_list = tk.Listbox(images_frame, height=7, exportselection=False)
        self.image_list.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        image_scroll = ttk.Scrollbar(images_frame, command=self.image_list.yview)
        image_scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.image_list.configure(yscrollcommand=image_scroll.set)
        self.image_list.bind("<<ListboxSelect>>", lambda _e: self._on_image_select())
        self.image_list.bind("<Double-Button-1>", lambda _e: self.open_preview_image())
        ttk.Label(
            parent,
            text="Frame labels are requested frame numbers; due to current readback behavior, the image content can lag.",
            wraplength=520,
            foreground="#8a5a00",
        ).pack(fill=tk.X, pady=(4, 0))
        self.preview_path_var = tk.StringVar()
        ttk.Label(parent, textvariable=self.preview_path_var, wraplength=520).pack(fill=tk.X)
        ttk.Button(parent, text="Open Image", command=self.open_preview_image).pack(anchor="e", pady=(6, 0))

    def _build_console_panel(self, parent):
        header = ttk.Frame(parent)
        header.pack(fill=tk.X)
        ttk.Label(header, text="CLI Console").pack(side=tk.LEFT)
        ttk.Label(header, textvariable=self.log_path_var).pack(side=tk.LEFT, padx=(10, 0))
        ttk.Button(header, text="Clear", command=self._clear_console).pack(side=tk.RIGHT)

        body = ttk.Frame(parent)
        body.pack(fill=tk.BOTH, expand=True, pady=(4, 0))
        self.console_text = tk.Text(body, height=8, wrap=tk.NONE, state=tk.DISABLED)
        y_scroll = ttk.Scrollbar(body, orient=tk.VERTICAL, command=self.console_text.yview)
        x_scroll = ttk.Scrollbar(body, orient=tk.HORIZONTAL, command=self.console_text.xview)
        self.console_text.configure(yscrollcommand=y_scroll.set, xscrollcommand=x_scroll.set)
        self.console_text.grid(row=0, column=0, sticky="nsew")
        y_scroll.grid(row=0, column=1, sticky="ns")
        x_scroll.grid(row=1, column=0, sticky="ew")
        body.rowconfigure(0, weight=1)
        body.columnconfigure(0, weight=1)

    def _labeled_entry(self, parent, label, variable, row):
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", pady=2)
        ttk.Entry(parent, textvariable=variable).grid(row=row, column=1, sticky="ew", padx=6, pady=2)
        parent.columnconfigure(1, weight=1)

    def _start_daemon(self):
        port = self._choose_port()
        self.port_var.set(str(port))
        command = [
            self.java_var.get(),
            f"-Xmx{self.xmx_var.get()}",
            "-jar",
            self.jar_var.get(),
            "serve",
            "--radiance-dir",
            str(Path(self.game_dir_var.get()) / "radiance"),
            "--save",
            self.save_id,
            "--out-dir",
            str(self.output_root),
            "--width",
            self.width_var.get(),
            "--height",
            self.height_var.get(),
            "--port",
            str(port),
            "--diagnostics",
            "true",
        ]
        self.daemon_ready = False
        self.process_exited = False
        self._write_log_line(f"\n=== Radiance Replay GUI daemon start {time.strftime('%Y-%m-%d %H:%M:%S')} ===\n")
        self._write_log_line(f"cwd={REPO_ROOT}\n")
        self._write_log_line(f"command={subprocess.list2cmdline(command)}\n")
        self.queue.put(("log", f"Log file: {self.log_file}"))
        self.queue.put(("log", f"Command: {subprocess.list2cmdline(command)}"))
        self.status_var.set("Starting daemon...")
        try:
            self.process = subprocess.Popen(
                command,
                cwd=str(REPO_ROOT),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
        except Exception as exc:
            message = f"Failed to start replay daemon: {exc}"
            self._write_log_line(message + "\n")
            self.queue.put(("error", message))
            return
        self.api = ApiClient(port)
        threading.Thread(target=self._read_process_output, daemon=True).start()
        threading.Thread(target=self._wait_for_daemon, daemon=True).start()

    def _choose_port(self):
        try:
            start = int(self.port_var.get())
        except ValueError:
            start = 17890
        for port in range(start, start + 100):
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                sock.settimeout(0.2)
                if sock.connect_ex(("127.0.0.1", port)) != 0:
                    if port != start:
                        self.queue.put(("log", f"Port {start} is busy; using {port} instead."))
                    return port
        raise RuntimeError(f"No free local port found from {start} to {start + 99}")

    def _read_process_output(self):
        process = self.process
        if process is None or process.stdout is None:
            return
        for line in process.stdout:
            self._write_log_line(line)
            text = line.rstrip("\r\n")
            if text:
                self.queue.put(("log", text))
                self.queue.put(("status", text))
        code = process.wait()
        self._write_log_line(f"=== replay daemon exit code {code} ===\n")
        self.queue.put(("process_exit", code))

    def _wait_for_daemon(self):
        deadline = time.time() + 240
        while time.time() < deadline:
            if self.process.poll() is not None:
                return
            try:
                self.api.get("/status")
                self.queue.put(("daemon_ready", None))
                return
            except Exception:
                time.sleep(1)
        self.queue.put(("error", f"Timed out waiting for daemon. Log: {self.log_file}"))

    def _drain_queue(self):
        try:
            while True:
                kind, value = self.queue.get_nowait()
                if kind == "status":
                    self.status_var.set(value[-180:])
                elif kind == "log":
                    self._append_console(value)
                elif kind == "error":
                    self.status_var.set(value)
                    self._append_console(value)
                    messagebox.showerror("Replay", value)
                elif kind == "daemon_ready":
                    self.daemon_ready = True
                    self.status_var.set("Daemon ready")
                    self._append_console("Daemon ready")
                    self.refresh_session()
                elif kind == "process_exit":
                    self._handle_process_exit(value)
                elif kind == "render_done":
                    self._show_render_result(value)
                elif kind == "pipeline_done":
                    self.status_var.set("Pipeline applied")
                    self.refresh_pipeline()
        except queue.Empty:
            pass
        self.after(100, self._drain_queue)

    def _append_console(self, text):
        if not hasattr(self, "console_text") or self.console_text is None:
            return
        self.console_text.configure(state=tk.NORMAL)
        self.console_text.insert(tk.END, str(text) + "\n")
        self.console_text.see(tk.END)
        self.console_text.configure(state=tk.DISABLED)

    def _clear_console(self):
        if not hasattr(self, "console_text") or self.console_text is None:
            return
        self.console_text.configure(state=tk.NORMAL)
        self.console_text.delete("1.0", tk.END)
        self.console_text.configure(state=tk.DISABLED)

    def _write_log_line(self, text):
        if self.log_file is None:
            return
        try:
            with self.log_file.open("a", encoding="utf-8", errors="replace") as handle:
                handle.write(text)
        except Exception:
            pass

    def _handle_process_exit(self, code):
        if self.process_exited:
            return
        self.process_exited = True
        message = f"Replay daemon exited with code {code}. Log: {self.log_file}"
        self._append_console(message)
        self.status_var.set(message)
        if self.closing:
            return
        if not self.daemon_ready or code != 0:
            messagebox.showerror("Replay daemon exited", message)

    def refresh_session(self):
        self.refresh_segments()
        self.refresh_pipeline()

    def refresh_segments(self):
        data = self.api.get("/segments")
        self.segment_list.delete(0, tk.END)
        self.segment_data = {}
        for item in data.get("segments", []):
            label = f"{item['segmentId']}  ({item['frameCount']} frames)"
            self.segment_list.insert(tk.END, label)
            self.segment_data[label] = item
        if self.segment_list.size():
            self.segment_list.selection_set(0)
            self._on_segment_select()

    def refresh_pipeline(self):
        data = self.api.get("/pipeline")
        self._last_pipeline_data = data
        self.preset_var.set(data.get("activePreset", ""))
        shader_values = []
        active_shader = ""
        self.shader_by_label = {}
        for pack in data.get("shaderPacks", []):
            suffix = " requires emission" if pack.get("requiresEmission") else ""
            label = f"{pack.get('displayName', '')}  [{pack.get('relativePath', '')}]{suffix}"
            shader_values.append(label)
            self.shader_by_label[label] = pack
            if pack.get("active"):
                active_shader = label
        self.shader_combo.configure(values=shader_values)
        if active_shader:
            self.shader_var.set(active_shader)
        self._build_attribute_widgets(data.get("attributes", []))

    def _build_attribute_widgets(self, attributes):
        for child in self.attr_inner.winfo_children():
            child.destroy()
        self.pipeline_attributes = {}
        self.pipeline_attr_getters = {}
        self.module_attributes = {}
        self.module_attr_getters = {}
        row = 0
        if attributes:
            ttk.Label(self.attr_inner, text="Shader Pack").grid(row=row, column=0, columnspan=2,
                sticky="w", pady=(2, 6))
            row += 1
        for attr in attributes:
            name = attr.get("name", "")
            attr_type = attr.get("type", "")
            value = attr.get("value", "")
            ttk.Label(self.attr_inner, text=name).grid(row=row, column=0, sticky="w", pady=2)
            var = tk.StringVar(value=value)
            self.pipeline_attributes[name] = var
            widget = self._make_attr_widget(self.attr_inner, self.pipeline_attr_getters, name,
                attr_type, var)
            widget.grid(row=row, column=1, sticky="ew", padx=6, pady=2)
            row += 1
        data = getattr(self, "_last_pipeline_data", {})
        for module in data.get("modules", []):
            module_name = module.get("name", "")
            module_attrs = module.get("attributes", [])
            if not module_attrs:
                continue
            ttk.Label(self.attr_inner, text=module_name).grid(row=row, column=0, columnspan=2,
                sticky="w", pady=(10, 6))
            row += 1
            self.module_attributes[module_name] = {}
            self.module_attr_getters[module_name] = {}
            for attr in module_attrs:
                name = attr.get("name", "")
                attr_type = attr.get("type", "")
                value = attr.get("value", "")
                ttk.Label(self.attr_inner, text=name).grid(row=row, column=0, sticky="w", pady=2)
                var = tk.StringVar(value=value)
                self.module_attributes[module_name][name] = var
                widget = self._make_attr_widget(self.attr_inner,
                    self.module_attr_getters[module_name], name, attr_type, var)
                widget.grid(row=row, column=1, sticky="ew", padx=6, pady=2)
                row += 1
        self.attr_inner.columnconfigure(1, weight=1)

    def _make_attr_widget(self, parent, getter_map, name, attr_type, variable):
        lower = (attr_type or "").lower()
        if lower == "bool":
            bool_var = tk.BooleanVar(value=variable.get() == "render_pipeline.true")
            getter_map[name] = (
                lambda item=bool_var: "render_pipeline.true" if item.get()
                else "render_pipeline.false"
            )
            return ttk.Checkbutton(parent, variable=bool_var)
        if lower.startswith("enum:"):
            values = (attr_type or "")[5:].split("-")
            return ttk.Combobox(parent, textvariable=variable, values=values, state="readonly")
        if lower.startswith("int_range:"):
            return self._range_widget(parent, variable, attr_type[10:], integer=True)
        if lower.startswith("float_range:"):
            return self._range_widget(parent, variable, attr_type[12:], integer=False)
        return ttk.Entry(parent, textvariable=variable)

    def _range_widget(self, parent, variable, raw_range, integer):
        frame = ttk.Frame(parent)
        try:
            start_raw, end_raw = raw_range.rsplit("-", 1)
            start = float(start_raw)
            end = float(end_raw)
        except Exception:
            return ttk.Entry(parent, textvariable=variable)
        current = float(variable.get()) if self._is_float(variable.get()) else start
        scale_var = tk.DoubleVar(value=current)
        scale = ttk.Scale(frame, from_=start, to=end, variable=scale_var)
        scale.pack(side=tk.LEFT, fill=tk.X, expand=True)
        label = ttk.Label(frame, width=8)
        label.pack(side=tk.RIGHT, padx=(6, 0))

        def sync(_event=None):
            value = scale_var.get()
            text = str(int(round(value))) if integer else f"{value:.3f}".rstrip("0").rstrip(".")
            variable.set(text)
            label.configure(text=text)

        scale.configure(command=sync)
        sync()
        return frame

    def _is_float(self, value):
        try:
            float(value)
            return True
        except Exception:
            return False

    def _on_segment_select(self):
        selection = self.segment_list.curselection()
        if not selection:
            return
        label = self.segment_list.get(selection[0])
        item = self.segment_data.get(label, {})
        self.info_text.delete("1.0", tk.END)
        self.info_text.insert(tk.END, json.dumps(item, indent=2, ensure_ascii=False))
        self.frame_list.delete(0, tk.END)
        for index in range(int(item.get("frameCount", 0))):
            self.frame_list.insert(tk.END, f"{index:04d}")
        if self.frame_list.size():
            self.frame_list.selection_set(0)

    def render_selected(self):
        selection = self.segment_list.curselection()
        if not selection:
            messagebox.showwarning("Segment", "Select a segment first.")
            return
        label = self.segment_list.get(selection[0])
        item = self.segment_data[label]
        frame_selection = self.frame_list.curselection()
        if not frame_selection:
            messagebox.showwarning("Frame", "Select one or more frames.")
            return
        frame_indices = [int(self.frame_list.get(i)) for i in frame_selection]
        self.status_var.set(f"Rendering {len(frame_indices)} frame(s)...")
        threading.Thread(target=self._render_worker, args=(item["segmentId"], frame_indices),
            daemon=True).start()

    def _render_worker(self, segment_id, frame_indices):
        try:
            results = []
            for offset, frame_index in enumerate(frame_indices, start=1):
                payload = {
                    "segment": segment_id,
                    "frameIndex": frame_index,
                    "out": "_preview",
                }
                self.queue.put(("status", f"Rendering frame {frame_index} ({offset}/{len(frame_indices)})..."))
                results.append(self.api.post("/play", payload, timeout=3600))
            self.queue.put(("render_done", results))
        except Exception as exc:
            self.queue.put(("error", str(exc)))

    def _show_render_result(self, result):
        self.status_var.set("Render complete")
        results = result if isinstance(result, list) else [result]
        self.rendered_images = [Path(item.get("image", "")) for item in results if item.get("image")]
        self.image_list.delete(0, tk.END)
        for path in self.rendered_images:
            self.image_list.insert(tk.END, path.name)
        if self.rendered_images:
            self.image_list.selection_set(len(self.rendered_images) - 1)
            self._show_image(self.rendered_images[-1])
            return
        image_path = Path(results[-1].get("image", "")) if results else Path("")
        if not str(image_path):
            out_dir = Path(results[-1].get("outputDir", "")) if results else Path("")
            image_path = out_dir / "0000.png"
        self._show_image(image_path)

    def _on_image_select(self):
        selection = self.image_list.curselection()
        if not selection:
            return
        index = selection[0]
        if index < len(self.rendered_images):
            self._show_image(self.rendered_images[index])

    def _show_image(self, image_path):
        self.preview_path_var.set(str(image_path))
        if Image is None or ImageTk is None or not image_path.exists():
            self.preview.configure(text=str(image_path))
            return
        image = Image.open(image_path)
        image.thumbnail((620, 520))
        self.preview_image = ImageTk.PhotoImage(image)
        self.preview.configure(image=self.preview_image, text="")

    def apply_pipeline(self):
        payload = {}
        if self.preset_var.get():
            payload["preset"] = self.preset_var.get()
        attrs = {}
        for name, var in self.pipeline_attributes.items():
            getter = self.pipeline_attr_getters.get(name)
            attrs[name] = getter() if getter else var.get()
        if attrs:
            payload["attributes"] = attrs
        module_attrs = {}
        for module_name, values in self.module_attributes.items():
            module_payload = {}
            getters = self.module_attr_getters.get(module_name, {})
            for name, var in values.items():
                getter = getters.get(name)
                module_payload[name] = getter() if getter else var.get()
            if module_payload:
                module_attrs[module_name] = module_payload
        if module_attrs:
            payload["moduleAttributes"] = module_attrs
        self.status_var.set("Applying pipeline...")
        threading.Thread(target=self._pipeline_worker, args=(payload,), daemon=True).start()

    def apply_shader_pack(self):
        shader_label = self.shader_var.get()
        if shader_label not in self.shader_by_label:
            messagebox.showwarning("Shader Pack", "Select a shader pack first.")
            return
        choice = self.shader_by_label[shader_label]
        save_info = getattr(self, "_last_pipeline_data", {}).get("saveInfo", {})
        if choice.get("requiresEmission") and not save_info.get("collectChunkEmission"):
            messagebox.showerror(
                "Shader Pack",
                "This shader pack requires emission data, but this replay was not recorded with collectChunkEmission=true.",
            )
            return
        payload = {
            "shaderPack": choice["id"],
            "shaderPackOnly": True,
        }
        self.status_var.set("Applying shader pack...")
        threading.Thread(target=self._pipeline_worker, args=(payload,), daemon=True).start()

    def _pipeline_worker(self, payload):
        try:
            self.api.post("/pipeline", payload, timeout=300)
            self.queue.put(("pipeline_done", None))
        except Exception as exc:
            self.queue.put(("error", str(exc)))

    def open_preview_image(self):
        path = self.preview_path_var.get()
        if path and Path(path).exists():
            os.startfile(path)

    def save_preview_image(self):
        source = Path(self.preview_path_var.get())
        if not source.exists():
            messagebox.showwarning("Preview", "Render a frame first.")
            return
        target = filedialog.asksaveasfilename(
            defaultextension=".png",
            filetypes=[("PNG image", "*.png")],
            initialfile=source.name,
        )
        if not target:
            return
        shutil.copyfile(source, target)
        self.status_var.set(f"Saved {target}")

    def _on_close(self):
        self.closing = True
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.destroy()


if __name__ == "__main__":
    ReplayGui().mainloop()
