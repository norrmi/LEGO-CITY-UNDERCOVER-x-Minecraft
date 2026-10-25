"""LCU-Installer: the window around install.py. Finds the game and the Minecraft instances, then converts and
installs everything in a background thread while the log scrolls.

    python gui.py            (build_exe.bat turns it into LCU-Installer.exe)
"""
import io
import os
import queue
import sys
import threading
import traceback
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import install  # noqa: E402
import launchers  # noqa: E402

TITLE = "LEGO City Undercover x Minecraft - Installer"
ICON_ICO = os.path.join(install.ROOT, "installer", "icon.ico")
ICON_PNG = os.path.join(install.ROOT, "mod", "src", "main", "resources", "assets", "lcucraft", "icon.png")
NOTICE = ("Unofficial fan-made project, not affiliated with TT Games, Warner Bros. or the LEGO Group. "
          "No game files are included: everything is converted on this PC from your own copy of LEGO City Undercover.")
BROWSE_INSTANCE = "Other folder..."


class LogStream(io.TextIOBase):
    """Stands in for stdout/stderr (a windowed exe has none) and hands each line to the window."""

    def __init__(self, q):
        self.q = q

    def write(self, s):
        self.q.put(s)
        return len(s)


class App:
    def __init__(self, root):
        self.root = root
        self.q = queue.Queue()
        self.worker = None
        root.title(TITLE)
        root.minsize(720, 560)
        try:
            root.iconbitmap(ICON_ICO)
        except tk.TclError:
            pass
        f = ttk.Frame(root, padding=14)
        f.pack(fill="both", expand=True)
        f.columnconfigure(1, weight=1)

        ttk.Label(f, text="LEGO City Undercover x Minecraft", font=("Segoe UI", 15, "bold")).grid(
            row=0, column=0, columnspan=3, sticky="w")
        ttk.Label(f, text=NOTICE, wraplength=680, foreground="#555").grid(
            row=1, column=0, columnspan=3, sticky="w", pady=(2, 12))

        ttk.Label(f, text="LEGO City Undercover folder").grid(row=2, column=0, sticky="w")
        self.game = tk.StringVar(value=launchers.find_game() or "")
        ttk.Entry(f, textvariable=self.game).grid(row=2, column=1, sticky="ew", padx=6)
        ttk.Button(f, text="Browse...", command=self.pick_game).grid(row=2, column=2)

        box = ttk.LabelFrame(f, text="Install into", padding=10)
        box.grid(row=3, column=0, columnspan=3, sticky="ew", pady=12)
        box.columnconfigure(1, weight=1)
        self.instances = launchers.find_instances()
        self.mode = tk.StringVar(value="instance" if self.instances or not launchers.vanilla_available() else "new")
        ttk.Radiobutton(box, text="an existing Minecraft 1.18.2 Fabric instance", variable=self.mode,
                        value="instance", command=self.sync).grid(row=0, column=0, columnspan=3, sticky="w")
        labels = [("* " if ok else "") + label for label, _, ok in self.instances] + [BROWSE_INSTANCE]
        self.instance = tk.StringVar(value=labels[0])
        self.combo = ttk.Combobox(box, textvariable=self.instance, values=labels, state="readonly")
        self.combo.grid(row=1, column=1, sticky="ew", padx=6, pady=(2, 0))
        self.combo.bind("<<ComboboxSelected>>", lambda e: self.instance_chosen())
        self.instance_dir = tk.StringVar(value=self.instances[0][1] if self.instances else "")
        ttk.Label(box, textvariable=self.instance_dir, foreground="#555").grid(row=2, column=1, sticky="w", padx=6)
        ttk.Label(box, text="* = Minecraft 1.18.2 with Fabric found in it", foreground="#555").grid(
            row=3, column=1, sticky="w", padx=6, pady=(0, 6))

        new_text = "a new profile in the official Minecraft Launcher (installs Fabric Loader for 1.18.2)"
        self.new_radio = ttk.Radiobutton(box, text=new_text, variable=self.mode, value="new", command=self.sync)
        self.new_radio.grid(row=4, column=0, columnspan=3, sticky="w")
        self.new_dir = tk.StringVar(value=os.path.join(launchers.VANILLA, "lcucraft"))
        self.new_entry = ttk.Entry(box, textvariable=self.new_dir)
        self.new_entry.grid(row=5, column=1, sticky="ew", padx=6)
        self.new_browse = ttk.Button(box, text="Browse...", command=self.pick_new_dir)
        self.new_browse.grid(row=5, column=2)
        hint = ("Close the Minecraft Launcher before installing." if launchers.vanilla_available()
                else "Minecraft Launcher not found - install it and start it once to use this option.")
        ttk.Label(box, text=hint, foreground="#555").grid(row=6, column=1, sticky="w", padx=6)
        if not launchers.vanilla_available():
            self.new_radio.state(["disabled"])

        self.extras = tk.BooleanVar(value=True)
        ttk.Checkbutton(f, text="Download Sodium + Iris for the shader pack (from Modrinth, if missing)",
                        variable=self.extras).grid(row=4, column=0, columnspan=3, sticky="w")
        self.convert = tk.BooleanVar(value=True)
        ttk.Checkbutton(f, text="Convert the game (uncheck to only update the mod and shader pack)",
                        variable=self.convert).grid(row=5, column=0, columnspan=3, sticky="w")

        self.button = ttk.Button(f, text="Install", command=self.start)
        self.button.grid(row=6, column=2, sticky="e", pady=10)
        self.bar = ttk.Progressbar(f, mode="determinate")
        self.bar.grid(row=6, column=0, columnspan=2, sticky="ew", padx=(0, 6))

        self.log = tk.Text(f, height=12, wrap="word", state="disabled", font=("Consolas", 9))
        self.log.grid(row=7, column=0, columnspan=3, sticky="nsew")
        f.rowconfigure(7, weight=1)
        sys.stdout = sys.stderr = LogStream(self.q)
        self.sync()
        self.poll()

    def sync(self):
        new = self.mode.get() == "new"
        self.combo.state(["disabled"] if new else ["!disabled", "readonly"])
        for w in (self.new_entry, self.new_browse):
            w.state(["!disabled"] if new else ["disabled"])

    def pick_game(self):
        d = filedialog.askdirectory(title="LEGO City Undercover folder (the one with LEVELS and AUDIO)")
        if d:
            self.game.set(os.path.normpath(d))

    def instance_chosen(self):
        i = self.combo.current()
        if i < len(self.instances):
            self.instance_dir.set(self.instances[i][1])
            return
        d = filedialog.askdirectory(title="Minecraft instance folder (the one with mods and saves)")
        if d:
            self.instance_dir.set(os.path.normpath(d))

    def pick_new_dir(self):
        d = filedialog.askdirectory(title="Folder for the new Minecraft profile")
        if d:
            self.new_dir.set(os.path.normpath(d))

    def write(self, s):
        self.log.configure(state="normal")
        self.log.insert("end", s)
        self.log.see("end")
        self.log.configure(state="disabled")

    def poll(self):
        try:
            while True:
                item = self.q.get_nowait()
                if isinstance(item, tuple):
                    self.finished(*item)
                else:
                    self.write(item)
        except queue.Empty:
            pass
        self.root.after(100, self.poll)

    def start(self):
        if self.worker is not None:
            self.root.destroy()
            return
        game = self.game.get().strip()
        new = self.mode.get() == "new"
        target = (self.new_dir.get() if new else self.instance_dir.get()).strip()
        if not game or not target:
            messagebox.showwarning(TITLE, "Choose the LEGO City Undercover folder and where to install.")
            return
        self.button.state(["disabled"])
        self.bar.configure(mode="indeterminate")
        self.bar.start(12)
        self.worker = threading.Thread(target=self.run, args=(game, target, new), daemon=True)
        self.worker.start()

    def run(self, game, target, new):
        try:
            install.check_game(game)
            if new:
                print("0/4 Minecraft Launcher profile")
                launchers.create_vanilla_profile(target, ICON_PNG)
            install.install(game, target, convert=self.convert.get(), extras=self.extras.get())
            self.q.put((True, new))
        except install.InstallError as e:
            self.q.put((False, str(e)))
        except Exception as e:  # anything else: keep the details in the log for a bug report
            traceback.print_exc()
            self.q.put((False, f"{type(e).__name__}: {e}"))

    def finished(self, ok, info):
        self.bar.stop()
        self.bar.configure(mode="determinate", value=100 if ok else 0)
        if ok:
            where = ("Start the Minecraft Launcher and play the profile\n'" + launchers.PROFILE_NAME + "'."
                     if info else "Start the instance in your launcher.")
            self.write("\nDone.\n")
            messagebox.showinfo(TITLE, "Installed!\n\n" + where + "\nOpen the world '" + install.WORLD
                                + "'. For shaders pick " + install.PACK
                                + " in Options > Video Settings > Shader Packs.")
            self.button.configure(text="Close")
            self.button.state(["!disabled"])
        else:
            self.write("\nFailed: " + info + "\n")
            messagebox.showerror(TITLE, info)
            self.worker = None
            self.button.state(["!disabled"])


def main():
    if len(sys.argv) > 1:
        # LCU-Installer.exe --game ... --instance ...: no window, same steps as install.py; a windowed exe has no
        # console, so the output goes to LCU-Installer.log next to the exe
        if sys.stdout is None:
            log = os.path.join(os.path.dirname(os.path.abspath(sys.executable)), "LCU-Installer.log")
            sys.stdout = sys.stderr = open(log, "w", encoding="utf-8", buffering=1)
        install.main()
        return
    try:
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):
        pass
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
