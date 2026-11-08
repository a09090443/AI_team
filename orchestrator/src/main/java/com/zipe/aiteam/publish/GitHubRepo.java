package com.zipe.aiteam.publish;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub 上的 owner/repo；由 origin 位址（https 或 ssh 寫法）解析，非 GitHub 位址回 null。 */
public record GitHubRepo(String owner, String name) {
    private static final Pattern REMOTE = Pattern.compile("github[.]com[:/]([^/]+)/([^/]+?)(?:[.]git)?/?$");

    public static GitHubRepo parse(String remote) {
        if (remote == null) return null;
        Matcher m = REMOTE.matcher(remote.trim());
        return m.find() ? new GitHubRepo(m.group(1), m.group(2)) : null;
    }

    public String apiBase() { return "https://api.github.com/repos/" + owner + "/" + name; }

    @Override public String toString() { return owner + "/" + name; }
}
