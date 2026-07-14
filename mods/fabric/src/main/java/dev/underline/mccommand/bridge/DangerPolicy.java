package dev.underline.mccommand.bridge;

import java.util.Locale;

final class DangerPolicy {
    private DangerPolicy() {}

    static String reason(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) normalized = normalized.substring(1).stripLeading();
        if (normalized.matches("^stop(\\s|$).*")) {
            return "server stop requires allow_dangerous=true";
        }
        if (normalized.matches("^(ban|ban-ip|banlist|deop|op|pardon|pardon-ip|whitelist)(\\s|$).*")) {
            return "permission and ban commands require allow_dangerous=true";
        }
        if (normalized.matches("^kill\\s+@(a|e)(\\[|\\s|$).*")) {
            return "mass kill commands require allow_dangerous=true";
        }
        if (normalized.matches("^(fill|clone)(\\s|$).*")) {
            return "large block edits require allow_dangerous=true";
        }
        return null;
    }
}
