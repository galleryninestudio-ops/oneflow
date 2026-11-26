package ai.oneflow.capabilities.files

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Enforces strict workspace path containment:
 * - Resolves workspace root via toRealPath()
 * - Rejects absolute paths or `..` escapes
 * - Detects and rejects symlinks that point outside the workspace root (including for non-existent target files)
 */
class WorkspacePathGuard(workspaceRoot: Path) {
    val rootRealPath: Path = workspaceRoot.toAbsolutePath().normalize().let { abs ->
        if (Files.exists(abs)) abs.toRealPath() else abs
    }

    fun resolveContained(relativePath: String, allowRoot: Boolean = false): Path {
        val trimmed = relativePath.trim()
        if (!allowRoot && trimmed.isEmpty()) {
            throw SecurityException("Path cannot be empty")
        }
        val candidateInput = Path.of(trimmed)
        if (candidateInput.isAbsolute) {
            throw SecurityException("Absolute paths are not permitted: '$relativePath'")
        }

        val normalized = rootRealPath.resolve(candidateInput).normalize()
        if (!normalized.startsWith(rootRealPath)) {
            throw SecurityException("Path escapes workspace root: '$relativePath'")
        }

        // Walk every segment from rootRealPath down to check symlinks on existing segments.
        val rel = rootRealPath.relativize(normalized)
        var cursor = rootRealPath
        for (segment in rel) {
            if (segment.toString().isEmpty()) continue
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor)) {
                    val resolvedLink = try {
                        cursor.toRealPath()
                    } catch (e: IOException) {
                        throw SecurityException("Broken or unreadable symlink rejected: '$relativePath'")
                    }
                    if (!resolvedLink.startsWith(rootRealPath)) {
                        throw SecurityException("Symlink escapes workspace root: '$relativePath'")
                    }
                }
            } else {
                // Remaining segments do not exist yet; parent `cursor.parent` is already verified real & contained.
                break
            }
        }

        if (Files.exists(normalized)) {
            val realTarget = normalized.toRealPath()
            if (!realTarget.startsWith(rootRealPath)) {
                throw SecurityException("Resolved path escapes workspace root: '$relativePath'")
            }
        }
        return normalized
    }

    fun relativize(path: Path): String =
        rootRealPath.relativize(path.normalize()).toString().replace('\\', '/')
}
