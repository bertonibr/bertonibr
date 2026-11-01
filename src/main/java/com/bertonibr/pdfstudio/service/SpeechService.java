package com.bertonibr.pdfstudio.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * Leitura em voz alta usando o sintetizador do próprio sistema operacional:
 * Windows (System.Speech), macOS (say) e Linux (espeak-ng / espeak / spd-say).
 */
public final class SpeechService {

    private Process process;
    private File tempFile;

    public synchronized void speak(String text, Runnable onFinish) throws IOException {
        stop();
        tempFile = File.createTempFile("pdfstudio-tts", ".txt");
        tempFile.deleteOnExit();
        Files.writeString(tempFile.toPath(), text, StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(command(tempFile)).redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        process = p;
        p.onExit().thenRun(() -> {
            synchronized (this) {
                if (process == p) process = null;
            }
            if (onFinish != null) onFinish.run();
        });
    }

    public synchronized boolean isSpeaking() {
        return process != null && process.isAlive();
    }

    public synchronized void stop() {
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            process = null;
        }
    }

    private static List<String> command(File f) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String path = f.getAbsolutePath();
        if (os.contains("win")) {
            String ps = "Add-Type -AssemblyName System.Speech;"
                    + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                    + "$v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'pt*' } | Select-Object -First 1;"
                    + "if ($v) { $s.SelectVoice($v.VoiceInfo.Name) };"
                    + "$s.Speak((Get-Content -LiteralPath '" + path.replace("'", "''") + "' -Raw -Encoding UTF8))";
            return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", ps);
        }
        if (os.contains("mac")) {
            return List.of("say", "-f", path);
        }
        for (String bin : List.of("espeak-ng", "espeak")) {
            if (onPath(bin)) return List.of(bin, "-v", "pt-br", "-f", path);
        }
        if (onPath("spd-say")) {
            return List.of("sh", "-c", "spd-say -w -l pt \"$(cat \"$0\")\"", path);
        }
        throw new IOException("Nenhum sintetizador de voz encontrado. Instale o espeak-ng (ex.: sudo apt install espeak-ng).");
    }

    private static boolean onPath(String bin) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, bin).canExecute()) return true;
        }
        return false;
    }
}
