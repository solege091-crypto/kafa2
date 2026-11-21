package com.example.afkkafa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Kafa resimlerini Claude'a gosterip "hangisi X?" diye sorar. Minecraft'a bagimli degil. */
final class ClaudeVision {

    static final String DEFAULT_MODEL = "claude-sonnet-5-5";

    static byte[] download(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestProperty("User-Agent", "afkkafa");
        try (InputStream is = con.getInputStream()) {
            return is.readAllBytes();
        }
    }

    /** Skin PNG'sinden kafanin on, sag, arka, sol ve ust yuzunu (ust katman dahil) yan yana buyutulmus PNG yapar. */
    static byte[] faceStrip(byte[] skinPng) throws IOException {
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(skinPng));
        if (skin == null) throw new IOException("doku okunamadi");
        int[][] base = {{8, 8}, {0, 8}, {24, 8}, {16, 8}, {8, 0}};
        int[][] hat = {{40, 8}, {32, 8}, {56, 8}, {48, 8}, {40, 0}};
        final int S = 10, GAP = 6;
        BufferedImage out = new BufferedImage(base.length * (8 * S + GAP), 8 * S, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < out.getWidth(); x++) for (int y = 0; y < out.getHeight(); y++) out.setRGB(x, y, 0x808080);

        for (int f = 0; f < base.length; f++) {
            int ox = f * (8 * S + GAP);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int c = pixel(skin, base[f][0] + x, base[f][1] + y);
                    int h = pixel(skin, hat[f][0] + x, hat[f][1] + y);
                    int rgb = ((h >>> 24) > 20) ? (h & 0xFFFFFF) : ((c >>> 24) > 20 ? (c & 0xFFFFFF) : 0x808080);
                    for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++) out.setRGB(ox + x * S + dx, y * S + dy, rgb);
                }
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bos);
        return bos.toByteArray();
    }

    private static int pixel(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) return 0;
        return img.getRGB(x, y);
    }

    private static JsonObject text(String t) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "text");
        o.addProperty("text", t);
        return o;
    }

    /** 1'den baslayan kafa numarasini dondurur, anlasilamazsa -1. */
    static int ask(String apiKey, String model, String word, List<String> terms, List<String> textureUrls) throws IOException {
        JsonArray content = new JsonArray();
        String names = String.join(" / ", terms);
        content.add(text("Minecraft server asks the player to click the head of: \"" + word + "\" (also: " + names + "). "
            + "Below are custom player-head skins. Each picture shows one head: front, right, back, left and top faces side by side. "
            + "Pixel art, so think about what the head depicts overall (animal, fruit, plant, object)."));
        for (int i = 0; i < textureUrls.size(); i++) {
            byte[] png = faceStrip(download(textureUrls.get(i)));
            content.add(text("Head " + (i + 1) + ":"));
            JsonObject src = new JsonObject();
            src.addProperty("type", "base64");
            src.addProperty("media_type", "image/png");
            src.addProperty("data", Base64.getEncoder().encodeToString(png));
            JsonObject img = new JsonObject();
            img.addProperty("type", "image");
            img.add("source", src);
            content.add(img);
        }
        content.add(text("Which head number depicts \"" + word + "\" (" + names + ")? Reply with only the number."));

        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(msg);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 20);
        body.add("messages", messages);

        HttpURLConnection con = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(8000);
        con.setReadTimeout(40000);
        con.setDoOutput(true);
        con.setRequestProperty("content-type", "application/json");
        con.setRequestProperty("x-api-key", apiKey);
        con.setRequestProperty("anthropic-version", "2023-06-01");
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String resp = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        if (code != 200) {
            throw new IOException("HTTP " + code + " " + (resp.length() > 160 ? resp.substring(0, 160) : resp));
        }
        String answer = JsonParser.parseString(resp).getAsJsonObject()
            .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        Matcher m = Pattern.compile("\\d+").matcher(answer);
        return m.find() ? Integer.parseInt(m.group()) : -1;
    }
}
