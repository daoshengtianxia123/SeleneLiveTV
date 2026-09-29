package com.daosheng.selenelivetv;

public class Channel {
    public final String name;
    public final String group;
    public final String url;

    public Channel(String name, String group, String url) {
        this.name = name == null || name.trim().isEmpty() ? "未命名频道" : name.trim();
        this.group = group == null ? "其他" : group.trim();
        this.url = url == null ? "" : url.trim();
    }

    @Override public String toString() {
        return name;
    }
}
