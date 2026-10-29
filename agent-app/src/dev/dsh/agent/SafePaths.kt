package dev.dsh.agent

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Comparator

internal object SafePaths {
    @JvmStatic
    @Throws(IOException::class)
    fun resolve(root: File, relative: String?, mustExist: Boolean): File {
        if (relative.isNullOrEmpty() || relative == ".") throw IOException("empty relative path")
        if (File(relative).isAbsolute || relative.startsWith("/") || relative.startsWith("\\") || relative.indexOf('\u0000') >= 0) {
            throw IOException("absolute path is not allowed")
        }
        val canonicalRoot = root.canonicalFile
        val result = File(canonicalRoot, relative).canonicalFile
        val prefix = canonicalRoot.path + File.separator
        if (!result.path.startsWith(prefix)) throw IOException("path escapes allowed directory")
        if (mustExist && !result.exists()) throw IOException("path does not exist")
        return result
    }

    @JvmStatic
    @Throws(IOException::class)
    fun removeTree(file: File) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            val children = file.listFiles() ?: throw IOException("cannot list directory")
            if (children.size > 10000) throw IOException("directory exceeds deletion budget")
            for (child in children) removeTree(child)
        }
        if (!file.delete() && file.exists()) throw IOException("cannot delete path")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun list(directory: File): List<File> {
        val files = directory.listFiles() ?: throw IOException("cannot list directory")
        val result = ArrayList<File>()
        for (file in files) {
            result.add(file)
            if (result.size >= 2000) break
        }
        result.sortWith(Comparator.comparing { it.name })
        return result
    }
}
