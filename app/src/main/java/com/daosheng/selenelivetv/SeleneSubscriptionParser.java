package com.daosheng.selenelivetv;

import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class SeleneSubscriptionParser {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private SeleneSubscriptionParser() {}

    public static List<String> extractLiveUrls(String encodedText) throws Exception {
        String s = encodedText == null ? "" : encodedText.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Selene订阅内容为空");

        byte[] decoded = decodeBase58(s);
        String jsonText = new String(decoded, StandardCharsets.UTF_8).trim();
        JSONObject root = new JSONObject(jsonText);
        JSONObject lives = root.optJSONObject("lives");
        if (lives == null) throw new IllegalArgumentException("Selene订阅缺少 lives 字段");

        List<String> urls = new ArrayList<>();
        Iterator<String> keys = lives.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject item = lives.optJSONObject(key);
            if (item == null) continue;
            String url = item.optString("url", "").trim();
            if (isHttpUrl(url)) urls.add(url);
        }

        if (urls.isEmpty()) throw new IllegalArgumentException("Selene订阅中没有有效直播地址");
        return urls;
    }

    private static boolean isHttpUrl(String s) {
        String x = s.toLowerCase();
        return x.startsWith("http://") || x.startsWith("https://");
    }

    private static byte[] decodeBase58(String input) throws Exception {
        if (input.isEmpty()) return new byte[0];

        BigInteger value = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(58);
        int leadingZeroes = 0;
        while (leadingZeroes < input.length() && input.charAt(leadingZeroes) == '1') {
            leadingZeroes++;
        }

        for (int i = 0; i < input.length(); i++) {
            int digit = ALPHABET.indexOf(input.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Selene Base58 内容包含非法字符");
            }
            value = value.multiply(base).add(BigInteger.valueOf(digit));
        }

        byte[] raw = value.equals(BigInteger.ZERO) ? new byte[0] : value.toByteArray();
        if (raw.length > 0 && raw[0] == 0) {
            byte[] t = new byte[raw.length - 1];
            System.arraycopy(raw, 1, t, 0, t.length);
            raw = t;
        }

        byte[] out = new byte[leadingZeroes + raw.length];
        System.arraycopy(raw, 0, out, leadingZeroes, raw.length);
        return out;
    }
}
