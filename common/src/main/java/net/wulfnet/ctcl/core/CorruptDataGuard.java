package net.wulfnet.ctcl.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs before vanilla loads our saved data. When vanilla can't decode a SavedData file it
 * logs, builds an <em>empty</em> instance, marks it dirty, and overwrites the file on the
 * next save. So an unreadable file is copied aside first, and never deleted or modified here.
 */
public final class CorruptDataGuard {
	private static final Logger LOGGER = LoggerFactory.getLogger("ctcl");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	public enum Outcome {
		/** Nothing on disk: a fresh start. */
		NO_FILE,
		/** The file decodes cleanly. */
		READABLE,
		/** The file is unreadable and a copy was made; vanilla may now replace the original. */
		BACKED_UP,
		/** The file is unreadable and no copy could be made. The caller must not let vanilla save over it. */
		BACKUP_FAILED
	}

	private CorruptDataGuard() {
	}

	/**
	 * @param decodes returns whether {@code file} decodes without any error. It is run
	 *                inside a catch: an exception counts as unreadable.
	 */
	public static Outcome check(Path file, Predicate<Path> decodes, Clock clock) {
		if (!Files.exists(file)) {
			return Outcome.NO_FILE;
		}
		boolean readable;
		try {
			readable = decodes.test(file);
		} catch (RuntimeException e) {
			LOGGER.error("Decoding {} threw; treating it as unreadable", file, e);
			readable = false;
		}
		if (readable) {
			return Outcome.READABLE;
		}
		Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + LocalDateTime.now(clock).format(STAMP));
		try {
			// No REPLACE_EXISTING: an existing backup is never overwritten.
			Files.copy(file, backup);
		} catch (IOException e) {
			LOGGER.error("{} is unreadable and could not be copied to {}. Loader settings for this dimension will not be saved, so the file is left intact.", file, backup, e);
			return Outcome.BACKUP_FAILED;
		}
		LOGGER.error("{} is unreadable. Copied it to {} before loading; every crafting table in this dimension starts off. Restore the copy with the world closed to recover.", file, backup);
		return Outcome.BACKED_UP;
	}
}
