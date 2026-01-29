package com.dripswap.bff.service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import com.dripswap.bff.config.FaucetV2Properties;
import com.dripswap.bff.util.Hashing;
import javax.imageio.ImageIO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class FaucetV2CaptchaService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int SVG_WIDTH = 180;
    private static final int SVG_HEIGHT = 60;
    private static final String KEY_PREFIX = "ds:v2:faucetv2:captcha:";

    private final FaucetV2Properties props;
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public FaucetV2CaptchaService(FaucetV2Properties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
    }

    public CaptchaChallenge issue(String clientIp) {
        FaucetV2Properties.Captcha captcha = props.getCaptcha();
        int ttlSeconds = Math.max(30, captcha.getTtlSeconds());
        int length = Math.min(8, Math.max(4, captcha.getLength()));

        String id = UUID.randomUUID().toString().replace("-", "");
        String answer = randomText(length);
        byte[] png = renderPng(answer);
        String imageData = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

        String payload = hashAnswer(answer) + "|" + nvl(hashIp(clientIp));
        redis.opsForValue().set(KEY_PREFIX + id, payload, Duration.ofSeconds(ttlSeconds));

        return new CaptchaChallenge(captcha.isEnabled(), id, imageData, ttlSeconds);
    }

    public boolean verify(String captchaId, String answer, String clientIp) {
        FaucetV2Properties.Captcha captcha = props.getCaptcha();
        if (!captcha.isEnabled()) return true;
        if (captchaId == null || captchaId.isBlank()) return false;
        if (answer == null || answer.isBlank()) return false;

        String payload = redis.opsForValue().get(KEY_PREFIX + captchaId);
        if (payload == null || payload.isBlank()) return false;
        String[] parts = payload.split("\\|", 2);
        String expectedHash = parts[0];
        String expectedIpHash = parts.length > 1 ? parts[1] : "";

        String actualHash = hashAnswer(answer);
        String actualIpHash = nvl(hashIp(clientIp));
        if (!expectedHash.equals(actualHash)) return false;
        if (!expectedIpHash.isBlank() && !expectedIpHash.equals(actualIpHash)) return false;

        redis.delete(KEY_PREFIX + captchaId);
        return true;
    }

    private String randomText(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int idx = random.nextInt(ALPHABET.length());
            out.append(ALPHABET.charAt(idx));
        }
        return out.toString();
    }

    private byte[] renderPng(String text) {
        BufferedImage image = new BufferedImage(SVG_WIDTH, SVG_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xF4, 0xF4, 0xF5));
            g.fillRect(0, 0, SVG_WIDTH, SVG_HEIGHT);

            g.setColor(new Color(0xD4, 0xD4, 0xD8));
            for (int i = 0; i < 6; i++) {
                int x1 = randBetween(0, SVG_WIDTH);
                int y1 = randBetween(0, SVG_HEIGHT);
                int x2 = randBetween(0, SVG_WIDTH);
                int y2 = randBetween(0, SVG_HEIGHT);
                g.drawLine(x1, y1, x2, y2);
            }

            g.setFont(new Font("Arial", Font.BOLD, 28));
            g.setColor(new Color(0x11, 0x18, 0x27));

            int charCount = text.length();
            int spacing = (SVG_WIDTH - 20) / Math.max(1, charCount);
            for (int i = 0; i < charCount; i++) {
                char c = text.charAt(i);
                int x = 10 + i * spacing + randBetween(-2, 4);
                int y = 40 + randBetween(-6, 6);
                int rotate = randBetween(-12, 12);
                AffineTransform old = g.getTransform();
                g.rotate(Math.toRadians(rotate), x, y);
                g.drawString(String.valueOf(c), x, y);
                g.setTransform(old);
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("captcha png writer unavailable");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to render captcha png", e);
        }
    }

    private int randBetween(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min);
    }

    private String hashAnswer(String answer) {
        FaucetV2Properties.Captcha captcha = props.getCaptcha();
        String secret = captcha.getSecretKey() == null ? "" : captcha.getSecretKey();
        String material = secret + "|" + answer.trim().toUpperCase(Locale.ROOT);
        return Hashing.sha256Hex(material);
    }

    private String hashIp(String ip) {
        if (ip == null || ip.isBlank()) return "";
        String salt = props.getIpHashSalt();
        String material = (salt == null ? "" : salt) + "|" + ip.trim().toLowerCase(Locale.ROOT);
        return Hashing.sha256Hex(material);
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    public record CaptchaChallenge(
            boolean enabled,
            String captchaId,
            String imageData,
            int expiresInSeconds
    ) {}
}
