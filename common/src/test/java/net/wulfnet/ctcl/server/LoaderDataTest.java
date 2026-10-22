package net.wulfnet.ctcl.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.wulfnet.ctcl.core.LoaderSize;
import net.wulfnet.ctcl.core.TablePos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LoaderDataTest {
	private static final TablePos A = new TablePos(40, 64, 40);
	private static final TablePos B = new TablePos(-1, -60, -17);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void roundTripsThroughNbt() {
		LoaderData data = new LoaderData();
		data.set(A, LoaderSize.FIVE);
		data.set(B, LoaderSize.NINE);

		Tag encoded = LoaderData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
		LoaderData decoded = LoaderData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

		assertEquals(Map.of(A, LoaderSize.FIVE, B, LoaderSize.NINE), decoded.snapshot());
		assertEquals(List.of(), LoaderData.problems(encoded));
	}

	@Test
	void anEmptyStoreIsValid() {
		Tag encoded = LoaderData.CODEC.encodeStart(NbtOps.INSTANCE, new LoaderData()).getOrThrow();
		assertEquals(List.of(), LoaderData.problems(encoded));
	}

	@Test
	void settingOffRemovesTheEntry() {
		LoaderData data = new LoaderData();
		data.set(A, LoaderSize.THREE);
		data.set(A, LoaderSize.OFF);
		assertEquals(Map.of(), data.snapshot());
	}

	@Test
	void onlyRealChangesMarkDirty() {
		LoaderData data = new LoaderData();
		data.set(A, LoaderSize.OFF);
		assertFalse(data.isDirty());

		data.set(A, LoaderSize.ONE);
		assertTrue(data.isDirty());

		data.setDirty(false);
		data.set(A, LoaderSize.ONE);
		assertFalse(data.isDirty());
	}

	@Test
	void anInvalidSizeIsAProblemAndIsSkippedOnLoad() {
		Tag tag = stored(1, entry(A, 4), entry(B, 9));

		assertEquals(1, LoaderData.problems(tag).size());
		LoaderData decoded = LoaderData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		assertEquals(Map.of(B, LoaderSize.NINE), decoded.snapshot());
	}

	@Test
	void aFutureVersionIsAProblem() {
		assertEquals(1, LoaderData.problems(stored(2, entry(A, 5))).size());
	}

	@Test
	void aMissingFieldIsAProblem() {
		CompoundTag noLoaders = new CompoundTag();
		noLoaders.putInt("version", 1);
		assertFalse(LoaderData.problems(noLoaders).isEmpty());
	}

	@Test
	void aMalformedEntryIsAProblemEvenThoughVanillaWouldLoadTheRest() {
		CompoundTag broken = new CompoundTag();
		broken.putString("pos", "not a position");
		broken.putInt("size", 5);
		Tag tag = stored(1, entry(A, 5), broken);

		assertFalse(LoaderData.problems(tag).isEmpty());
	}

	@Test
	void theWrongRootTypeIsAProblem() {
		assertFalse(LoaderData.problems(new ListTag()).isEmpty());
	}

	private static CompoundTag entry(TablePos pos, int size) {
		CompoundTag entry = new CompoundTag();
		entry.put("pos", new IntArrayTag(new int[] {pos.x(), pos.y(), pos.z()}));
		entry.putInt("size", size);
		return entry;
	}

	private static CompoundTag stored(int version, CompoundTag... entries) {
		CompoundTag root = new CompoundTag();
		root.putInt("version", version);
		ListTag list = new ListTag();
		for (CompoundTag entry : entries) {
			list.add(entry);
		}
		root.put("loaders", list);
		return root;
	}
}
