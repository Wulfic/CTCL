package net.wulfnet.ctcl.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.wulfnet.ctcl.core.LoaderSize;
import net.wulfnet.ctcl.core.LoaderStore;
import net.wulfnet.ctcl.core.TablePos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-dimension table sizes, saved as {@code data/ctcl_loaders.dat}. Vanilla ignores
 * unknown files in {@code data/}, so this file is inert if the mod is removed.
 */
public final class LoaderData extends SavedData implements LoaderStore {
	static final int VERSION = 1;
	private static final Logger LOGGER = LoggerFactory.getLogger("ctcl");

	record Entry(BlockPos pos, int size) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
			Codec.INT.fieldOf("size").forGetter(Entry::size)
		).apply(i, Entry::new));
	}

	private record Stored(int version, List<Entry> loaders) {
		static final Codec<Stored> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("version").forGetter(Stored::version),
			Entry.CODEC.listOf().fieldOf("loaders").forGetter(Stored::loaders)
		).apply(i, Stored::new));
	}

	public static final Codec<LoaderData> CODEC = Stored.CODEC.xmap(LoaderData::fromStored, LoaderData::toStored);

	/** {@code null} DataFixTypes: Fabric API skips datafixing for it, so vanilla fixers never touch our schema. */
	public static final SavedDataType<LoaderData> TYPE = new SavedDataType<>("ctcl_loaders", LoaderData::new, CODEC, null);

	private final Map<TablePos, LoaderSize> sizes = new LinkedHashMap<>();

	public LoaderData() {
	}

	@Override
	public LoaderSize get(TablePos pos) {
		return sizes.getOrDefault(pos, LoaderSize.OFF);
	}

	@Override
	public void set(TablePos pos, LoaderSize size) {
		if (get(pos) == size) {
			return;
		}
		if (size.isOff()) {
			sizes.remove(pos);
		} else {
			sizes.put(pos, size);
		}
		setDirty();
	}

	@Override
	public Map<TablePos, LoaderSize> snapshot() {
		return Map.copyOf(sizes);
	}

	/**
	 * Strict check used before vanilla loads the file: any decode error, unknown version,
	 * or invalid size is a problem. Vanilla's own load is lenient and would drop them.
	 */
	static List<String> problems(Tag data) {
		List<String> problems = new ArrayList<>();
		DataResult<Stored> result = Stored.CODEC.parse(NbtOps.INSTANCE, data);
		result.error().ifPresent(error -> problems.add(error.message()));
		result.result().ifPresent(stored -> {
			if (stored.version() < 1 || stored.version() > VERSION) {
				problems.add("unsupported version " + stored.version() + " (this build reads " + VERSION + ")");
			}
			for (Entry entry : stored.loaders()) {
				if (LoaderSize.ofSide(entry.size()).isEmpty()) {
					problems.add("invalid size " + entry.size() + " at " + entry.pos().toShortString());
				}
			}
		});
		return problems;
	}

	private static LoaderData fromStored(Stored stored) {
		LoaderData data = new LoaderData();
		for (Entry entry : stored.loaders()) {
			TablePos pos = toTablePos(entry.pos());
			LoaderSize size = LoaderSize.ofSide(entry.size()).orElse(null);
			if (size == null) {
				LOGGER.warn("Skipping stored loader at {}: invalid size {}", pos, entry.size());
			} else if (!size.isOff() && data.sizes.put(pos, size) != null) {
				LOGGER.warn("Duplicate stored loader at {}; keeping size {}", pos, size.side());
			}
		}
		return data;
	}

	private Stored toStored() {
		List<Entry> entries = new ArrayList<>(sizes.size());
		sizes.forEach((pos, size) -> entries.add(new Entry(toBlockPos(pos), size.side())));
		return new Stored(VERSION, entries);
	}

	public static TablePos toTablePos(BlockPos pos) {
		return new TablePos(pos.getX(), pos.getY(), pos.getZ());
	}

	public static BlockPos toBlockPos(TablePos pos) {
		return new BlockPos(pos.x(), pos.y(), pos.z());
	}
}
