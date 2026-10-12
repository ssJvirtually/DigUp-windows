package com.digup.worker;

import com.digup.engine.Vectors;
import com.digup.model.AppSettings;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Client for the Python embedding worker communicating over stdio JSON-lines.
 */
public final class WorkerClient implements Closeable {

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final Gson gson = new Gson();
    private int nextId = 1;
    private Map<String, Object> info = new HashMap<>();

    public WorkerClient(boolean textOnly) throws Exception {
        Path scriptPath = ensureWorkerScript();
        String pythonExec = findPythonExecutable();

        List<String> cmd = new ArrayList<>(List.of(pythonExec, scriptPath.toString()));
        if (textOnly) {
            cmd.add("--text-only");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.process = pb.start();

        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // Read initial ready line
        String readyLine = reader.readLine();
        if (readyLine == null) {
            throw new IOException("Worker exited immediately without ready signal");
        }
        JsonObject readyJson = gson.fromJson(readyLine, JsonObject.class);
        if (!readyJson.has("ready") || !readyJson.get("ready").getAsBoolean()) {
            String err = readyJson.has("error") ? readyJson.get("error").getAsString() : "unknown error";
            throw new IOException("Worker failed to start: " + err);
        }

        if (readyJson.has("info")) {
            JsonObject inf = readyJson.getAsJsonObject("info");
            for (String k : inf.keySet()) {
                this.info.put(k, inf.get(k).getAsString());
            }
        }
    }

    public Map<String, Object> getInfo() {
        return info;
    }

    public synchronized float[][] embedTexts(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) return new float[0][0];
        JsonObject req = new JsonObject();
        int id = nextId++;
        req.addProperty("id", id);
        req.addProperty("op", "text");
        JsonArray arr = new JsonArray();
        for (String t : texts) arr.add(t);
        req.add("inputs", arr);

        return sendRequest(id, req, texts.size());
    }

    public synchronized float[][] embedImages(List<String> paths, int budget) throws Exception {
        if (paths == null || paths.isEmpty()) return new float[0][0];
        JsonObject req = new JsonObject();
        int id = nextId++;
        req.addProperty("id", id);
        req.addProperty("op", "image");
        req.addProperty("budget", budget);
        JsonArray arr = new JsonArray();
        for (String p : paths) arr.add(p);
        req.add("inputs", arr);

        return sendRequest(id, req, paths.size());
    }

    public synchronized float[][] embedAudio(List<String> paths) throws Exception {
        if (paths == null || paths.isEmpty()) return new float[0][0];
        JsonObject req = new JsonObject();
        int id = nextId++;
        req.addProperty("id", id);
        req.addProperty("op", "audio");
        JsonArray arr = new JsonArray();
        for (String p : paths) arr.add(p);
        req.add("inputs", arr);

        return sendRequest(id, req, paths.size());
    }

    private float[][] sendRequest(int id, JsonObject req, int expectedCount) throws Exception {
        writer.write(gson.toJson(req));
        writer.write("\n");
        writer.flush();

        String line = reader.readLine();
        if (line == null) throw new IOException("Worker process died unexpectedly");

        JsonObject reply = gson.fromJson(line, JsonObject.class);
        if (!reply.has("ok") || !reply.get("ok").getAsBoolean()) {
            String err = reply.has("error") ? reply.get("error").getAsString() : "unknown worker error";
            throw new IOException("Worker query failed: " + err);
        }

        String base64 = reply.get("vectors").getAsString();
        byte[] raw = Base64.getDecoder().decode(base64);
        int dim = reply.has("dim") ? reply.get("dim").getAsInt() : Vectors.DIMENSION;

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[][] out = new float[expectedCount][dim];

        for (int i = 0; i < expectedCount; i++) {
            for (int d = 0; d < dim; d++) {
                if (buf.hasRemaining()) {
                    out[i][d] = buf.getFloat();
                }
            }
        }
        return out;
    }

    @Override
    public synchronized void close() {
        try {
            JsonObject req = new JsonObject();
            req.addProperty("id", 0);
            req.addProperty("op", "quit");
            writer.write(gson.toJson(req) + "\n");
            writer.flush();
            writer.close();
            reader.close();
            process.destroyForcibly();
        } catch (Exception ignored) {}
    }

    private static Path ensureWorkerScript() throws IOException {
        Path dir = AppSettings.getAppDataDir();
        if (!Files.exists(dir)) Files.createDirectories(dir);
        Path target = dir.resolve("digup_worker.py");

        try (InputStream in = WorkerClient.class.getResourceAsStream("/digup_worker.py")) {
            if (in != null) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    private static String findPythonExecutable() {
        String[] candidates = {"python", "python3", "py", "C:\\Python312\\python.exe", "C:\\Python311\\python.exe"};
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "--version").start();
                if (p.waitFor() == 0) return c;
            } catch (Exception ignored) {}
        }
        return "python";
    }
}
