package com.ant.robot.utils;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves a single map folder against the configured PCD directory. */
public final class MapPaths {
    private MapPaths() {}

    public static Path globalMap(String baseDirectory, String folder) {
        if (baseDirectory == null || baseDirectory.isBlank()) {
            throw new IllegalArgumentException("PCD directory must be configured");
        }
        if (folder == null || folder.isBlank() || folder.equals(".") || folder.equals("..")
                || folder.contains("/") || folder.contains("\\") || folder.contains(":")) {
            throw new IllegalArgumentException("Map name must be a single folder name");
        }
        return Paths.get(baseDirectory).toAbsolutePath().normalize()
                .resolve(folder).resolve("GlobalMap.pcd");
    }
}
