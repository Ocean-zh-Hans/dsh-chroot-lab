package dev.dsh.agent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class SafePaths {
    private SafePaths() {
    }

    static File resolve(File root, String relative, boolean mustExist) throws IOException {
        if (relative == null || relative.isEmpty() || relative.equals(".")) throw new IOException("empty relative path");
        if (new File(relative).isAbsolute() || relative.startsWith("/") || relative.startsWith("\\")
                || relative.indexOf('\0') >= 0) throw new IOException("absolute path is not allowed");
        File canonicalRoot = root.getCanonicalFile();
        File result = new File(canonicalRoot, relative).getCanonicalFile();
        String prefix = canonicalRoot.getPath() + File.separator;
        if (!result.getPath().startsWith(prefix)) throw new IOException("path escapes allowed directory");
        if (mustExist && !result.exists()) throw new IOException("path does not exist");
        return result;
    }

    static void removeTree(File file) throws IOException {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return;
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("cannot list directory");
            if (children.length > 10000) throw new IOException("directory exceeds deletion budget");
            for (File child : children) removeTree(child);
        }
        if (!file.delete() && file.exists()) throw new IOException("cannot delete path");
    }

    static List<File> list(File directory) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("cannot list directory");
        List<File> result = new ArrayList<>();
        for (File file : files) {
            result.add(file);
            if (result.size() >= 2000) break;
        }
        result.sort(Comparator.comparing(File::getName));
        return result;
    }
}
