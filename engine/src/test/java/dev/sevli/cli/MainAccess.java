package dev.sevli.cli;

import dev.sevli.Config;

import java.util.List;

/** Package bridge for tests in other packages. */
public final class MainAccess {
    private MainAccess() {}

    public static int remove(Config config, List<String> args) throws Exception {
        return CleanCommand.remove(config, args);
    }
}
