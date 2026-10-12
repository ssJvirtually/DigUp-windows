package com.digup.model;

import java.util.*;

public class IndexOptions {
    public boolean code = false;
    public int codeStretch = 1800;
    public int maxCodeBytes = 1 << 20;      // 1 MB
    public int maxNotebookBytes = 20 << 20; // 20 MB
    public boolean includeCode = false;
    public boolean includeDatasets = false;

    public int imagePixels = 1024;
    public int minSide = 256;
    public int minText = 200;
    public int docChunkSize = 1800;
    public int docChunkOverlap = 200;

    public int bigPDFPages = 50;
    public int pdfStep = 30;
    public int docStep = 40;
    public int maxPages = 5000;
    public int maxChunks = 5000;

    public int photoBudget = 140;
    public int screenshotBudget = 280;
    public int pageBudget = 280;
    public int frameBudget = 70;
    public double frameInterval = 3.0;
    public double audioWindow = 30.0;
    public double audioHop = 25.0;

    public List<String> excludedFolders = new ArrayList<>();
    public Set<String> excludedExtensions = new HashSet<>();

    public String fingerprint() {
        if (code) {
            return "code1 lines" + codeStretch;
        }
        return "photo" + photoBudget + " shot" + screenshotBudget + " page" + pageBudget +
               " frame" + frameBudget + "/" + frameInterval + "s audio" + audioWindow + "/" + audioHop + "s";
    }

    public static Set<String> parseExtensions(String text) {
        Set<String> set = new HashSet<>();
        if (text == null) return set;
        String[] parts = text.split("[,\\s]+");
        for (String p : parts) {
            String clean = p.replaceAll("^[*.]+", "").toLowerCase().trim();
            if (!clean.isEmpty()) set.add(clean);
        }
        return set;
    }
}
