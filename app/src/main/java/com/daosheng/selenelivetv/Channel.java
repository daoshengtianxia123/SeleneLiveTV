package com.daosheng.selenelivetv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Channel {
    public final String name;
    public final String group;

    // 当前实际播放地址。为了兼容原有代码保留 public 字段。
    public volatile String url;

    private final List<String> urls = new ArrayList<>();
    private int lineIndex = 0;

    public Channel(String name, String group, String url) {
        this.name = name == null || name.trim().isEmpty() ? "未命名频道" : name.trim();
        this.group = group == null ? "其他" : group.trim();
        addUrl(url);
        if (urls.isEmpty()) urls.add("");
        this.url = urls.get(0);
    }

    public synchronized void addUrl(String value) {
        String u = value == null ? "" : value.trim();
        if (u.isEmpty() || urls.contains(u)) return;
        urls.add(u);
        if (urls.size() == 1) {
            lineIndex = 0;
            url = u;
        }
    }

    public synchronized int lineCount() {
        return urls.size();
    }

    public synchronized int lineNumber() {
        return Math.min(lineIndex + 1, urls.size());
    }

    public synchronized boolean hasBackupLine() {
        return urls.size() > 1;
    }

    public synchronized boolean switchToNextLine() {
        if (urls.size() <= 1) return false;
        int next = lineIndex + 1;
        if (next >= urls.size()) next = 0;
        if (next == lineIndex) return false;
        lineIndex = next;
        url = urls.get(lineIndex);
        return true;
    }

    public synchronized boolean switchToNextUntriedLine(int attemptIndex) {
        if (urls.size() <= 1) return false;
        if (attemptIndex <= 0 || attemptIndex >= urls.size()) return false;
        lineIndex = attemptIndex;
        url = urls.get(lineIndex);
        return true;
    }

    public synchronized void resetPrimaryLine() {
        if (urls.isEmpty()) return;
        lineIndex = 0;
        url = urls.get(0);
    }

    public synchronized List<String> getUrls() {
        return Collections.unmodifiableList(new ArrayList<>(urls));
    }

    @Override public String toString() {
        return name;
    }
}
