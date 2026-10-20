package com.zipe.aiteam.sandbox;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Only terminates the exact transient service belonging to one runtime invocation. */
public final class SandboxUnitCleanup {
    private SandboxUnitCleanup() {}
    public enum Status { CONFIRMED, FAILED, UNKNOWN }
    public record Result(Status status, String unit, String detail) {}
    record Reply(int exit, String output) {}
    @FunctionalInterface interface Commands { Reply run(List<String> argv, Duration timeout) throws Exception; }

    public static Result terminate(String unit, Process launcher) {
        return terminate(unit, launcher, SandboxUnitCleanup::run, Path.of("/sys/fs/cgroup"));
    }

    static Result terminate(String unit, Process launcher, Commands commands, Path cgroups) {
        boolean interrupted = Thread.interrupted();
        Result result;
        try {
            if (unit == null || !unit.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*") || unit.contains(".."))
                return new Result(Status.FAILED, unit, "Invalid exact unit name");
            String service = unit.endsWith(".service") ? unit : unit + ".service";
            Reply stopped = commands.run(List.of("systemctl", "--user", "stop", service), Duration.ofSeconds(8));
            if (stopped.exit() != 0) {
                commands.run(List.of("systemctl", "--user", "kill", "--kill-whom=all", "--signal=SIGKILL", service), Duration.ofSeconds(2));
                stopped = commands.run(List.of("systemctl", "--user", "stop", service), Duration.ofSeconds(3));
            }
            Reply shown = commands.run(List.of("systemctl", "--user", "show", service,
                    "--property=LoadState", "--property=ActiveState", "--property=ControlGroup"), Duration.ofSeconds(2));
            String state = shown.output();
            if (stopped.exit() == 0 && state.contains("LoadState=not-found")) {
                result = new Result(Status.CONFIRMED, unit, "Stopped and collected");
            } else if (shown.exit() == 0 && (state.contains("ActiveState=inactive") || state.contains("ActiveState=failed"))) {
                String group = state.lines().filter(s -> s.startsWith("ControlGroup=")).map(s -> s.substring(13)).findFirst().orElse("");
                if (group.isBlank()) result = new Result(Status.CONFIRMED, unit, "Stopped; no control group");
                else {
                    Path base = cgroups.toAbsolutePath().normalize();
                    Path path = base.resolve(group.startsWith("/") ? group.substring(1) : group).normalize();
                    if (!path.startsWith(base) || path.equals(base)) result = new Result(Status.UNKNOWN, unit, "Invalid control group path");
                    else if (Files.notExists(path)) result = new Result(Status.CONFIRMED, unit, "Control group removed");
                    else if (Files.readString(path.resolve("cgroup.events")).lines().anyMatch(s -> s.trim().equals("populated 0")))
                        result = new Result(Status.CONFIRMED, unit, "Control group empty");
                    else result = new Result(Status.FAILED, unit, "Control group remains populated");
                }
            } else result = new Result(Status.UNKNOWN, unit, "Unit termination could not be confirmed");
        } catch (InterruptedException e) {
            interrupted = true;
            result = new Result(Status.UNKNOWN, unit, "Cleanup interrupted");
        } catch (Exception e) {
            result = new Result(Status.UNKNOWN, unit, "Cleanup unavailable: " + e.getClass().getSimpleName());
        } finally {
            if (launcher != null) {
                launcher.destroyForcibly();
                try { launcher.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        return result;
    }

    private static Reply run(List<String> argv, Duration timeout) throws Exception {
        Process command = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!command.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return new Reply(-1, "");
            return new Reply(command.exitValue(), new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (command.isAlive()) command.destroyForcibly();
        }
    }
}
