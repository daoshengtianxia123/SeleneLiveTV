package com.daosheng.selenelivetv;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaylistParser {
    private static final Pattern GROUP = Pattern.compile("group-title=\\\"([^\\\"]*)\\\"");

    private PlaylistParser() {}

    public static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<>();
        if (text == null) return out;
        String[] lines = text.replace("\r", "").split("\n");
        String pendingName = null;
        String pendingGroup = "其他";
        String currentTextGroup = "其他";

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                pendingName = comma >= 0 ? line.substring(comma + 1).trim() : "频道";
                Matcher m = GROUP.matcher(line);
                pendingGroup = m.find() ? m.group(1) : "其他";
                continue;
            }

            if (isUrl(line)) {
                out.add(new Channel(pendingName, pendingGroup, line));
                pendingName = null;
                pendingGroup = "其他";
                continue;
            }

            int comma = line.indexOf(',');
            if (comma > 0) {
                String left = line.substring(0, comma).trim();
                String right = line.substring(comma + 1).trim();

                if ("#genre#".equalsIgnoreCase(right)) {
                    currentTextGroup = left.isEmpty() ? "其他" : left;
                    continue;
                }

                if (isUrl(right)) {
                    out.add(new Channel(left, currentTextGroup, right));
                }
            }
        }
        return out;
    }

    private static boolean isUrl(String s) {
        String x = s.toLowerCase();
        return x.startsWith("http://") || x.startsWith("https://") ||
               x.startsWith("rtsp://") || x.startsWith("rtmp://");
    }
}
