package pl.holovis;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Sterowanie Spotify na komputerze (bez logowania, bez Web API):
 *  - odczyt utworu: tytuł okna Spotify (Windows) / osascript (macOS) / playerctl (Linux),
 *  - sterowanie: systemowe klawisze multimedialne (Windows) / osascript / playerctl.
 */
public final class Spotify {
    private Spotify() {}

    public static volatile String track = "";
    public static volatile boolean playing = false;
    public static volatile boolean running = false;

    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WIN = OS.contains("win");
    private static final boolean MAC = OS.contains("mac");

    private static Process poller, controller;
    private static boolean pollActive, hookAdded;
    private static int gen;

    public static synchronized void sync() {
        if (!hookAdded) {
            hookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(Spotify::shutdown));
        }
        if (HoloConfig.get().spotify) {
            startPolling();
            if (WIN) ensureController();
        } else {
            stopPolling();
        }
    }

    // ---------------- sterowanie ----------------
    public static void next() { command("next"); }
    public static void previous() { command("prev"); }
    public static void toggle() { command("toggle"); }

    private static synchronized void command(String cmd) {
        if (WIN) {
            try {
                ensureController();
                OutputStream os = controller.getOutputStream();
                os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception e) {
                System.err.println("[HoloVis] Spotify: nie udało się wysłać polecenia: " + e);
            }
            return;
        }
        String[] c;
        if (MAC) {
            String a = cmd.equals("next") ? "next track" : cmd.equals("prev") ? "previous track" : "playpause";
            c = new String[]{"osascript", "-e", "tell application \"Spotify\" to " + a};
        } else {
            String a = cmd.equals("next") ? "next" : cmd.equals("prev") ? "previous" : "play-pause";
            c = new String[]{"playerctl", "-p", "spotify", a};
        }
        Thread t = new Thread(() -> run(c), "holovis-spotify-cmd");
        t.setDaemon(true);
        t.start();
    }

    private static void ensureController() {
        try {
            if (controller != null && controller.isAlive()) return;
            String script = "$w = New-Object -ComObject WScript.Shell; "
                    + "while(($l = [Console]::In.ReadLine()) -ne $null){ "
                    + "if($l -eq 'next'){ $w.SendKeys([char]176) } "
                    + "elseif($l -eq 'prev'){ $w.SendKeys([char]177) } "
                    + "elseif($l -eq 'toggle'){ $w.SendKeys([char]179) } }";
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script).redirectErrorStream(true).start();
            controller = p;
            Thread d = new Thread(() -> {
                try { p.getInputStream().transferTo(OutputStream.nullOutputStream()); } catch (Exception ignored) {}
            }, "holovis-spotify-drain");
            d.setDaemon(true);
            d.start();
        } catch (Exception e) {
            System.err.println("[HoloVis] Spotify: nie udało się uruchomić kontrolera: " + e);
        }
    }

    // ---------------- odczyt utworu ----------------
    private static void startPolling() {
        if (pollActive) return;
        pollActive = true;
        final int my = ++gen;
        Thread t = new Thread(() -> { if (WIN) pollWindows(my); else pollOther(my); }, "holovis-spotify");
        t.setDaemon(true);
        t.start();
    }

    private static void stopPolling() {
        pollActive = false;
        gen++;
        if (poller != null) { poller.destroy(); poller = null; }
        running = false;
        playing = false;
        track = "";
    }

    private static void shutdown() {
        try { if (poller != null) poller.destroy(); } catch (Exception ignored) {}
        try { if (controller != null) controller.destroy(); } catch (Exception ignored) {}
    }

    private static void pollWindows(int my) {
        String script = "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false; "
                + "while($true){ "
                + "$p = Get-Process -Name Spotify -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle } | Select-Object -First 1; "
                + "if($p){ Write-Output ('T|' + $p.MainWindowTitle) } else { Write-Output 'N|' }; "
                + "Start-Sleep -Milliseconds 1000 }";
        try {
            Process pr = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script).redirectErrorStream(true).start();
            synchronized (Spotify.class) {
                if (my != gen) { pr.destroy(); return; }
                poller = pr;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (my == gen && (line = r.readLine()) != null) handleWindows(line);
            }
        } catch (Exception e) {
            System.err.println("[HoloVis] Spotify: błąd odczytu: " + e);
        }
    }

    private static void handleWindows(String line) {
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
        if (line.startsWith("T|")) {
            String title = line.substring(2).trim();
            running = true;
            if (title.matches("(?i)^spotify( (free|premium))?$")) {
                playing = false;
            } else {
                playing = true;
                track = title;
            }
        } else if (line.startsWith("N|")) {
            running = false;
            playing = false;
            track = "";
        }
    }

    private static void pollOther(int my) {
        String[] cmd = MAC
                ? new String[]{"osascript", "-e",
                "if application \"Spotify\" is running then tell application \"Spotify\" to return (artist of current track) & \" - \" & (name of current track) & \"|\" & (player state as string)"}
                : new String[]{"playerctl", "-p", "spotify", "metadata", "--format", "{{artist}} - {{title}}|{{status}}"};
        while (my == gen) {
            String out = run(cmd);
            int i = out.lastIndexOf('|');
            if (out.isEmpty() || i < 0) {
                running = false;
                playing = false;
            } else {
                running = true;
                track = out.substring(0, i).trim();
                playing = out.substring(i + 1).toLowerCase().contains("play");
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    private static String run(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(3, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            return "";
        }
    }
}
