package net.wulfnet.ctcl.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CorruptDataGuardTest {
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T20:15:30Z"), ZoneOffset.UTC);
	private static final byte[] CONTENT = {1, 2, 3, 42};

	@TempDir
	Path dir;

	@Mock
	private Predicate<Path> decodes;

	@Test
	void missingFileIsNotDecodedOrCopied() throws IOException {
		Path file = dir.resolve("ctcl_loaders.dat");

		assertEquals(CorruptDataGuard.Outcome.NO_FILE, CorruptDataGuard.check(file, decodes, CLOCK));

		verify(decodes, never()).test(any());
		assertEquals(List.of(), listDir());
	}

	@Test
	void readableFileIsLeftAloneWithNoCopy() throws IOException {
		Path file = write("ctcl_loaders.dat");
		when(decodes.test(file)).thenReturn(true);

		assertEquals(CorruptDataGuard.Outcome.READABLE, CorruptDataGuard.check(file, decodes, CLOCK));

		assertEquals(List.of("ctcl_loaders.dat"), listDir());
		assertArrayEquals(CONTENT, Files.readAllBytes(file));
	}

	@Test
	void unreadableFileIsCopiedAsideAndTheOriginalIsUntouched() throws IOException {
		Path file = write("ctcl_loaders.dat");
		when(decodes.test(file)).thenReturn(false);

		assertEquals(CorruptDataGuard.Outcome.BACKED_UP, CorruptDataGuard.check(file, decodes, CLOCK));

		Path backup = dir.resolve("ctcl_loaders.dat.corrupt-20260923-201530");
		assertArrayEquals(CONTENT, Files.readAllBytes(backup));
		assertArrayEquals(CONTENT, Files.readAllBytes(file));
	}

	@Test
	void aDecoderThatThrowsCountsAsUnreadable() throws IOException {
		Path file = write("ctcl_loaders.dat");
		when(decodes.test(file)).thenThrow(new IllegalStateException("bad nbt"));

		assertEquals(CorruptDataGuard.Outcome.BACKED_UP, CorruptDataGuard.check(file, decodes, CLOCK));

		assertTrue(Files.exists(dir.resolve("ctcl_loaders.dat.corrupt-20260923-201530")));
	}

	@Test
	void anExistingBackupIsNeverOverwritten() throws IOException {
		Path file = write("ctcl_loaders.dat");
		Path backup = dir.resolve("ctcl_loaders.dat.corrupt-20260923-201530");
		byte[] older = {9, 9};
		Files.write(backup, older);
		when(decodes.test(file)).thenReturn(false);

		assertEquals(CorruptDataGuard.Outcome.BACKUP_FAILED, CorruptDataGuard.check(file, decodes, CLOCK));

		assertArrayEquals(older, Files.readAllBytes(backup));
		assertArrayEquals(CONTENT, Files.readAllBytes(file));
	}

	private Path write(String name) throws IOException {
		Path file = dir.resolve(name);
		Files.write(file, CONTENT);
		return file;
	}

	private List<String> listDir() throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}
}
